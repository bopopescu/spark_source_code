/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler

import java.nio.ByteBuffer
import java.util.concurrent.RejectedExecutionException

import scala.language.existentials
import scala.util.control.NonFatal

import org.apache.spark._
import org.apache.spark.TaskState.TaskState
import org.apache.spark.serializer.SerializerInstance
import org.apache.spark.util.{ThreadUtils, Utils}

/**
 * Runs a thread pool that deserializes and remotely fetches (if necessary) task results.
 */
private[spark] class TaskResultGetter(sparkEnv: SparkEnv, scheduler: TaskSchedulerImpl)
  extends Logging {

  private val THREADS = sparkEnv.conf.getInt("spark.resultGetter.threads", 4)
  private val getTaskResultExecutor = ThreadUtils.newDaemonFixedThreadPool(
    THREADS, "task-result-getter")

  protected val serializer = new ThreadLocal[SerializerInstance] {
    override def initialValue(): SerializerInstance = {
      sparkEnv.closureSerializer.newInstance()
    }
  }
/*
* 处理成功的Task,启动线程池，默认的线程池是4个线程，用户可以自行设置
* */
  def enqueueSuccessfulTask(
    taskSetManager: TaskSetManager, tid: Long, serializedData: ByteBuffer) {
    getTaskResultExecutor.execute(new Runnable {
      override def run(): Unit = Utils.logUncaughtExceptions {
        try {
          // 对获取的结果序列化
          val (result, size) = serializer.get().deserialize[TaskResult[_]](serializedData) match {
              // 获取直接结果，并判断大小是否符合要求
            case directResult: DirectTaskResult[_] =>
              if (!taskSetManager.canFetchMoreResults(serializedData.limit())) {
                return
              }
              // deserialize "value" without holding any lock so that it won't block other threads.
              // We should call it here, so that when it's called again in
              // "TaskSetManager.handleSuccessfulTask", it does not need to deserialize the value.
              directResult.value()
              (directResult, serializedData.limit())
            case IndirectTaskResult(blockId, size) =>   // 获取间接的结果
              if (!taskSetManager.canFetchMoreResults(size)) {
                // dropped by executor if size is larger than maxResultSize
                sparkEnv.blockManager.master.removeBlock(blockId)
                return
              }
              logDebug("Fetching indirect task result for TID %s".format(tid))
              scheduler.handleTaskGettingResult(taskSetManager, tid)
              val serializedTaskResult = sparkEnv.blockManager.getRemoteBytes(blockId)
              if (!serializedTaskResult.isDefined) {
                /* We won't be able to get the task result if the machine that ran the task failed
                 * between when the task ended and when we tried to fetch the result, or if the
                 * block manager had to flush the result. */
                scheduler.handleFailedTask(
                  taskSetManager, tid, TaskState.FINISHED, TaskResultLost)
                return
              }
              val deserializedResult = serializer.get().deserialize[DirectTaskResult[_]](
                serializedTaskResult.get)
              sparkEnv.blockManager.master.removeBlock(blockId)
              (deserializedResult, size)
          }
          result.metrics.setResultSize(size)
          scheduler.handleSuccessfulTask(taskSetManager, tid, result)
        } catch {
          case cnf: ClassNotFoundException =>
            val loader = Thread.currentThread.getContextClassLoader
            taskSetManager.abort("ClassNotFound with classloader: " + loader)
          // Matching NonFatal so we don't catch the ControlThrowable from the "return" above.
          case NonFatal(ex) =>
            logError("Exception while getting task result", ex)
            taskSetManager.abort("Exception while getting task result: %s".format(ex))
        }
      }
    })
  }

  /*
  *  处理失败的Task
  * */
  def enqueueFailedTask(taskSetManager: TaskSetManager, tid: Long, taskState: TaskState,
    serializedData: ByteBuffer) {
    var reason : TaskEndReason = UnknownReason
    try {
      getTaskResultExecutor.execute(new Runnable {
        override def run(): Unit = Utils.logUncaughtExceptions {
          val loader = Utils.getContextOrSparkClassLoader
          try {
            if (serializedData != null && serializedData.limit() > 0) {
              reason = serializer.get().deserialize[TaskEndReason](
                serializedData, loader)
            }
          } catch {
            case cnd: ClassNotFoundException =>
              // Log an error but keep going here -- the task failed, so not catastrophic
              // if we can't deserialize the reason.
              logError(
                "Could not deserialize TaskEndReason: ClassNotFound with classloader " + loader)
            case ex: Exception => {}
          }
          scheduler.handleFailedTask(taskSetManager, tid, taskState, reason)
        }
      })
    } catch {
      case e: RejectedExecutionException if sparkEnv.isStopped =>
        // ignore it
    }
  }

  def stop() {
    getTaskResultExecutor.shutdownNow()
  }
}
