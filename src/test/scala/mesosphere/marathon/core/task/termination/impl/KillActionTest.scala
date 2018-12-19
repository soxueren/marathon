package mesosphere.marathon
package core.task.termination.impl

import mesosphere.UnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.instance.{Instance, LocalVolumeId, TestInstanceBuilder}
import mesosphere.marathon.state.PathId
import org.scalatest.prop.TableDrivenPropertyChecks

class KillActionTest extends UnitTest with TableDrivenPropertyChecks {

  val clock = new SettableClock()
  val appId = PathId("/test")

  lazy val localVolumeId = LocalVolumeId(appId, "unwanted-persistent-volume", "uuid1")
  lazy val residentLaunchedInstance: Instance = TestInstanceBuilder.newBuilder(appId).
    addTaskResidentLaunched(Seq(localVolumeId)).
    getInstance()

  lazy val residentUnreachableInstance: Instance = TestInstanceBuilder.newBuilder(appId).
    addTaskUnreachable(Seq(localVolumeId)).
    getInstance()

  lazy val unreachableInstance: Instance = TestInstanceBuilder.newBuilder(appId).addTaskUnreachable().getInstance()
  lazy val runningInstance: Instance = TestInstanceBuilder.newBuilder(appId).addTaskLaunched().getInstance()

  "computeKillAction" when {
    Table(
      ("name", "instance", "expected"),
      ("an unreachable reserved instance", (residentUnreachableInstance, false), KillAction.Noop),
      ("an unreachable reserved instance with wipe", (residentUnreachableInstance, true), KillAction.ExpungeFromState),
      ("a running reserved instance", (residentLaunchedInstance, false), KillAction.IssueKillRequest),
      ("an unreachable ephemeral instance", (unreachableInstance, false), KillAction.ExpungeFromState),
      ("a running ephemeral instance", (runningInstance, false), KillAction.IssueKillRequest)
    ).
      foreach {
        case (name, (instance, wipe), expected) =>
          s"killing ${name}" should {
            s"result in ${expected}" in {
              KillAction(instance.instanceId, instance.tasksMap.keys, Some(instance), wipe).shouldBe(expected)
            }
          }
      }
  }
}
