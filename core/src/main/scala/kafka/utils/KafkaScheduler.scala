/**
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

package kafka.utils

import java.util.concurrent._
import atomic._
import org.apache.kafka.common.utils.KafkaThread

/**
 * A scheduler for running jobs
 * 
 * This interface controls a job scheduler that allows scheduling either repeating background jobs 
 * that execute periodically or delayed one-time actions that are scheduled in the future.
 */
trait Scheduler {
  
  /**
   * Initialize this scheduler so it is ready to accept scheduling of tasks
   */
  def startup(): Unit
  
  /**
   * Shutdown this scheduler. When this method is complete no more executions of background tasks will occur. 
   * This includes tasks scheduled with a delayed execution.
   */
  def shutdown(): Unit
  
  /**
   * Check if the scheduler has been started
   */
  def isStarted: Boolean
  
  /**
   * Schedule a task
   * @param name The name of this task
   * @param delay The amount of time to wait before the first execution
   * @param period The period with which to execute the task. If < 0 the task will execute only once.
   * @param unit The unit for the preceding times.
   * @return A Future object to manage the task scheduled.
   */
  def schedule(name: String, fun: ()=>Unit, delay: Long = 0, period: Long = -1, unit: TimeUnit = TimeUnit.MILLISECONDS) : ScheduledFuture[_]
}

/**
 * A scheduler based on java.util.concurrent.ScheduledThreadPoolExecutor
 * 
 * It has a pool of kafka-scheduler- threads that do the actual work.
 * 
 * @param threads The number of threads in the thread pool
 * @param threadNamePrefix The name to use for scheduler threads. This prefix will have a number appended to it.
 * @param daemon If true the scheduler threads will be "daemon" threads and will not block jvm shutdown.
 */
@threadsafe
// kafka调度器 重点
// 因为标记了线程安全,所以这里面的如果含有多线程设计, 那么就会上锁. 可以查看有没有sync 或者 lock关键字
class KafkaScheduler(val threads: Int, 
                     val threadNamePrefix: String = "kafka-scheduler-", 
                     daemon: Boolean = true) extends Scheduler with Logging {
  private var executor: ScheduledThreadPoolExecutor = null
  private val schedulerThreadId = new AtomicInteger(0)

  override def startup(): Unit = {
    debug("Initializing task scheduler.")
    // 使用sync关键字. 锁住了当前对象this  
    this synchronized {
      // 调度器是否已经启动了
      if(isStarted)
        throw new IllegalStateException("This scheduler has already been started!")
      executor = new ScheduledThreadPoolExecutor(threads)
      executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
      executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
      executor.setRemoveOnCancelPolicy(true)
      executor.setThreadFactory(new ThreadFactory() {
                                  def newThread(runnable: Runnable): Thread = 
                                    new KafkaThread(threadNamePrefix + schedulerThreadId.getAndIncrement(), runnable, daemon)
                                })
    }
  }
  
  override def shutdown(): Unit = {
    debug("Shutting down task scheduler.")
    // We use the local variable to avoid NullPointerException if another thread shuts down scheduler at same time.
    val cachedExecutor = this.executor
    if (cachedExecutor != null) {
      this synchronized {
        cachedExecutor.shutdown()
        this.executor = null
      }
      cachedExecutor.awaitTermination(1, TimeUnit.DAYS)
    }
  }

  // 计划执行一次. 
  def scheduleOnce(name: String, fun: () => Unit): Unit = {
    schedule(name, fun, delay = 0L, period = -1L, unit = TimeUnit.MILLISECONDS)
  }

/**
name 任务名称
fun 任务执行器
delay 延迟时间
*/
  def schedule(name: String, fun: () => Unit, delay: Long, period: Long, unit: TimeUnit): ScheduledFuture[_] = {
    debug("Scheduling task %s with initial delay %d ms and period %d ms."
        .format(name, TimeUnit.MILLISECONDS.convert(delay, unit), TimeUnit.MILLISECONDS.convert(period, unit)))
        // 每次调度都得锁住. 为什么定时任务都需要这样啊
    this synchronized {
      // 确保定时任务已经开启
      ensureRunning()
      // 构建一个new Runnable
      val runnable = CoreUtils.runnable {
        try {
          trace("Beginning execution of scheduled task '%s'.".format(name))
          fun()
        } catch {
          case t: Throwable => error(s"Uncaught exception in scheduled task '$name'", t)
        } finally {
          trace("Completed execution of scheduled task '%s'.".format(name))
        }
      }
      if(period >= 0)
        executor.scheduleAtFixedRate(runnable, delay, period, unit)
      else
        executor.schedule(runnable, delay, unit)
    }
  }

  /**
   * Package private for testing.
   */
  private[utils] def taskRunning(task: ScheduledFuture[_]): Boolean = {
    executor.getQueue().contains(task)
  }

  def resizeThreadPool(newSize: Int): Unit = {
    executor.setCorePoolSize(newSize)
  }
  
  def isStarted: Boolean = {
    this synchronized {
      executor != null
    }
  }
  
  private def ensureRunning(): Unit = {
    if (!isStarted)
      throw new IllegalStateException("Kafka scheduler is not running.")
  }
}
