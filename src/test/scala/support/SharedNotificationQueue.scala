package ru.bitec.app.ops
package support

import java.util.concurrent.locks.ReentrantLock

/** Exclusive use of the notification outbox for one test.
  *
  * A worker claims due deliveries of every organization, by design; that is how production runs.
  * In a test run, suites execute in parallel against one database, so a worker of one suite would
  * claim, lock or send the deliveries another suite has just written and is about to assert on.
  * Every test that writes deliveries or runs a worker takes this lock, so each one sees the queue
  * as its own. Tests elsewhere are unaffected.
  */
object SharedNotificationQueue {

  private val lock = new ReentrantLock(true)

  def exclusive[A](body: => A): A = {
    lock.lock()
    try body
    finally lock.unlock()
  }
}
