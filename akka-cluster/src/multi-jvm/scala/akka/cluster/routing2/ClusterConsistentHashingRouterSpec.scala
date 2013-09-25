/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.routing2

import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.Props
import akka.cluster.MultiNodeClusterSpec
import akka.pattern.ask
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.routing2.ConsistentHashingRouter
import akka.routing2.ConsistentHashingRouter.ConsistentHashMapping
import akka.routing2.ConsistentHashingRouter.ConsistentHashableEnvelope
import akka.routing2.CurrentRoutees
import akka.routing2.FromConfig
import akka.routing2.RouterRoutees
import akka.testkit._
import akka.routing2.ActorRefRoutee
import akka.routing2.ConsistentHashingPool
import akka.cluster.routing.ClusterRouterSettings

object ClusterConsistentHashingRouterMultiJvmSpec extends MultiNodeConfig {

  class Echo extends Actor {
    def receive = {
      case _ ⇒ sender ! self
    }
  }

  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
      common-router-settings = {
        router = consistent-hashing
        nr-of-instances = 10
        cluster {
          enabled = on
          max-nr-of-instances-per-node = 2
        }
        routing2 = on # FIXME #3549 temporary
      }

      akka.actor.deployment {
        /router1 = ${common-router-settings}
        /router3 = ${common-router-settings}
        /router4 = ${common-router-settings}
      }
      """)).
    withFallback(MultiNodeClusterSpec.clusterConfig))

}

class ClusterConsistentHashingRouterMultiJvmNode1 extends ClusterConsistentHashingRouterSpec
class ClusterConsistentHashingRouterMultiJvmNode2 extends ClusterConsistentHashingRouterSpec
class ClusterConsistentHashingRouterMultiJvmNode3 extends ClusterConsistentHashingRouterSpec

abstract class ClusterConsistentHashingRouterSpec extends MultiNodeSpec(ClusterConsistentHashingRouterMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender with DefaultTimeout {
  import ClusterConsistentHashingRouterMultiJvmSpec._

  lazy val router1 = system.actorOf(Props[Echo].withRouter(FromConfig()), "router1")

  def currentRoutees(router: ActorRef) =
    Await.result(router ? CurrentRoutees, remaining).asInstanceOf[RouterRoutees].routees

  /**
   * Fills in self address for local ActorRef
   */
  private def fullAddress(actorRef: ActorRef): Address = actorRef.path.address match {
    case Address(_, _, None, None) ⇒ cluster.selfAddress
    case a                         ⇒ a
  }

  "A cluster router with a consistent hashing pool" must {
    "start cluster with 2 nodes" taggedAs LongRunningTest in {
      awaitClusterUp(first, second)
      enterBarrier("after-1")
    }

    "create routees from configuration" in {
      runOn(first) {
        // it may take some time until router receives cluster member events
        awaitAssert { currentRoutees(router1).size must be(4) }
        val routees = currentRoutees(router1)
        routees.map { case ActorRefRoutee(ref) ⇒ fullAddress(ref) }.toSet must be(Set(address(first), address(second)))
      }
      enterBarrier("after-2")
    }

    "select destination based on hashKey" in {
      runOn(first) {
        router1 ! ConsistentHashableEnvelope(message = "A", hashKey = "a")
        val destinationA = expectMsgType[ActorRef]
        router1 ! ConsistentHashableEnvelope(message = "AA", hashKey = "a")
        expectMsg(destinationA)
      }
      enterBarrier("after-2")
    }

    "deploy routees to new member nodes in the cluster" taggedAs LongRunningTest in {

      awaitClusterUp(first, second, third)

      runOn(first) {
        // it may take some time until router receives cluster member events
        awaitAssert { currentRoutees(router1).size must be(6) }
        val routees = currentRoutees(router1)
        routees.map { case ActorRefRoutee(ref) ⇒ fullAddress(ref) }.toSet must be(roles.map(address).toSet)
      }

      enterBarrier("after-3")
    }

    "deploy programatically defined routees to the member nodes in the cluster" taggedAs LongRunningTest in {
      runOn(first) {
        val router2 = system.actorOf(Props[Echo].withRouter(ClusterPool(local = ConsistentHashingPool(nrOfInstances = 0),
          settings = ClusterRouterSettings(totalInstances = 10, maxInstancesPerNode = 2, useRole = None))), "router2")
        // it may take some time until router receives cluster member events
        awaitAssert { currentRoutees(router2).size must be(6) }
        val routees = currentRoutees(router2)
        routees.map { case ActorRefRoutee(ref) ⇒ fullAddress(ref) }.toSet must be(roles.map(address).toSet)
      }

      enterBarrier("after-4")
    }

    "handle combination of configured router and programatically defined hashMapping" taggedAs LongRunningTest in {
      runOn(first) {
        def hashMapping: ConsistentHashMapping = {
          case s: String ⇒ s
        }

        val router3 = system.actorOf(Props[Echo].withRouter(ConsistentHashingPool(nrOfInstances = 0, hashMapping = hashMapping)), "router3")

        assertHashMapping(router3)
      }

      enterBarrier("after-5")
    }

    "handle combination of configured router and programatically defined hashMapping and ClusterRouterConfig" taggedAs LongRunningTest in {
      runOn(first) {
        def hashMapping: ConsistentHashMapping = {
          case s: String ⇒ s
        }

        val router4 = system.actorOf(Props[Echo].withRouter(ClusterPool(
          local = ConsistentHashingPool(nrOfInstances = 0, hashMapping = hashMapping),
          settings = ClusterRouterSettings(totalInstances = 10, maxInstancesPerNode = 1, useRole = None))), "router4")

        assertHashMapping(router4)
      }

      enterBarrier("after-6")
    }

    def assertHashMapping(router: ActorRef): Unit = {
      // it may take some time until router receives cluster member events
      awaitAssert { currentRoutees(router).size must be(6) }
      val routees = currentRoutees(router)
      routees.map { case ActorRefRoutee(ref) ⇒ fullAddress(ref) }.toSet must be(roles.map(address).toSet)

      router ! "a"
      val destinationA = expectMsgType[ActorRef]
      router ! "a"
      expectMsg(destinationA)
    }

  }
}