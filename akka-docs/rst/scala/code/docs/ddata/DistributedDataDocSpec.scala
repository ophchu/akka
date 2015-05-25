/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.ddata

import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.ORSet
import akka.cluster.ddata.Replicator
import akka.cluster.ddata.Replicator._
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.cluster.ddata.PNCounter
import akka.cluster.ddata.GSet
import akka.cluster.ddata.Flag
import akka.testkit.TestProbe
import akka.actor.ActorRef
import akka.cluster.ddata.PNCounterMap
import akka.cluster.ddata.ORMultiMap
import akka.cluster.ddata.LWWRegister
import akka.cluster.ddata.ReplicatedData
import akka.serialization.SerializationExtension

object DistributedDataDocSpec {

  //#data-bot
  import scala.concurrent.forkjoin.ThreadLocalRandom
  import akka.actor.Actor
  import akka.actor.ActorLogging
  import akka.cluster.Cluster
  import akka.cluster.ddata.DistributedData
  import akka.cluster.ddata.ORSet
  import akka.cluster.ddata.Replicator
  import akka.cluster.ddata.Replicator._

  object DataBot {
    private case object Tick
  }

  class DataBot extends Actor with ActorLogging {
    import DataBot._

    val replicator = DistributedData(context.system).replicator
    implicit val cluster = Cluster(context.system)

    import context.dispatcher
    val tickTask = context.system.scheduler.schedule(5.seconds, 5.seconds, self, Tick)

    replicator ! Subscribe("key", self)

    def receive = {
      case Tick =>
        val s = ThreadLocalRandom.current().nextInt(97, 123).toChar.toString
        if (ThreadLocalRandom.current().nextBoolean()) {
          // add
          log.info("Adding: {}", s)
          replicator ! Update("key", ORSet.empty[String], WriteLocal)(_ + s)
        } else {
          // remove
          log.info("Removing: {}", s)
          replicator ! Update("key", ORSet.empty[String], WriteLocal)(_ - s)
        }

      case _: UpdateResponse => // ignore

      case Changed("key", ORSet(elements)) =>
        log.info("Current elements: {}", elements)
    }

    override def postStop(): Unit = tickTask.cancel()

  }
  //#data-bot

}

class DistributedDataDocSpec extends AkkaSpec("""
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.netty.tcp.port = 0

    #//#serializer-config
    akka.actor {
      serializers {
        two-phase-set = "docs.ddata.protobuf.TwoPhaseSetSerializer"
      }
      serialization-bindings {
        "docs.ddata.TwoPhaseSet" = two-phase-set
      }
    }
    #//#serializer-config
""") {
  import Replicator._

  import DistributedDataDocSpec._

  "demonstrate update" in {
    val probe = TestProbe()
    implicit val self = probe.ref

    //#update
    implicit val cluster = Cluster(system)
    val replicator = DistributedData(system).replicator

    replicator ! Update("counter1", PNCounter(), WriteLocal)(_ + 1)

    val writeTo3 = WriteTo(n = 3, timeout = 1.second)
    replicator ! Update("set1", GSet.empty[String], writeTo3)(_ + "hello")

    val writeQuorum = WriteQuorum(timeout = 5.seconds)
    replicator ! Update("set2", ORSet.empty[String], writeQuorum)(_ + "hello")

    val writeAll = WriteAll(timeout = 5.seconds)
    replicator ! Update("active", Flag.empty, writeAll)(_.switchOn)
    //#update

    probe.expectMsgType[UpdateResponse] match {
      //#update-response1
      case UpdateSuccess("counter1", req) => // ok
      //#update-response1
      case unexpected                     => fail("Unexpected response: " + unexpected)
    }

    probe.expectMsgType[UpdateResponse] match {
      //#update-response2
      case UpdateSuccess("set1", req)  => // ok
      case UpdateTimeout("set1", req)  =>
      // write to 3 nodes failed within 1.second
      //#update-response2
      case UpdateSuccess("set2", None) =>
      case unexpected                  => fail("Unexpected response: " + unexpected)
    }
  }

  "demonstrate update with read consistency level" in {
    val probe = TestProbe()
    implicit val self = probe.ref

    //#read-update
    implicit val cluster = Cluster(system)
    val replicator = DistributedData(system).replicator

    val readQuorum = ReadQuorum(timeout = 2.seconds)
    val writeQuorum = WriteQuorum(timeout = 3.seconds)
    val newElement = "a345"
    val update =
      Update("set3", ORSet.empty[String], readQuorum, writeQuorum) { currentSet =>
        if (currentSet.size >= 10) {
          val removeElements =
            currentSet.elements.toSeq.sorted.take(currentSet.size - 9)
          val setWith9Elements =
            removeElements.foldLeft(currentSet) { (s, elem) => s - elem }
          setWith9Elements + newElement
        } else
          currentSet + newElement
      }
    replicator ! update
    //#read-update

    probe.expectMsgType[UpdateResponse] match {
      //#read-update-response
      case UpdateSuccess("set3", req) => // ok
      case ReadFailure("set3", req)   =>
      // read from quorum falied within 2.seconds
      case UpdateTimeout("set3", req) =>
      // write to quorum failed within 3.seconds
      //#read-update-response
      case unexpected                 => fail("Unexpected response: " + unexpected)
    }
  }

  "demonstrate update with request context" in {
    import Actor.Receive
    val probe = TestProbe()
    implicit val self = probe.ref
    def sender() = self

    //#update-request-context
    implicit val cluster = Cluster(system)
    val replicator = DistributedData(system).replicator
    val writeTwo = WriteTo(n = 2, timeout = 3.second)

    def receive: Receive = {
      case "increment" =>
        // incoming command to increase the counter
        val upd = Update("counter1", PNCounter(), writeTwo, request = Some(sender()))(_ + 1)
        replicator ! upd

      case UpdateSuccess("counter1", Some(replyTo: ActorRef)) =>
        replyTo ! "ack"
      case UpdateTimeout("counter1", Some(replyTo: ActorRef)) =>
        replyTo ! "nack"
    }
    //#update-request-context
  }

  "demonstrate get" in {
    val probe = TestProbe()
    implicit val self = probe.ref

    //#get
    val replicator = DistributedData(system).replicator

    replicator ! Get("counter1", ReadLocal)

    val readFrom3 = ReadFrom(n = 3, timeout = 1.second)
    replicator ! Get("set1", readFrom3)

    val readQuorum = ReadQuorum(timeout = 5.seconds)
    replicator ! Get("set2", readQuorum)

    val readAll = ReadAll(timeout = 5.seconds)
    replicator ! Get("active", readAll)
    //#get

    probe.expectMsgType[GetResponse] match {
      //#get-response1
      case GetSuccess("counter1", PNCounter(value), req) => // ok
      case NotFound("counter1", req)                     => // key counter1 does not exist
      //#get-response1
      case unexpected                                    => fail("Unexpected response: " + unexpected)
    }

    probe.expectMsgType[GetResponse] match {
      //#get-response2
      case GetSuccess("set1", GSet(elements), req)   => // ok
      case GetFailure("set1", req)                   =>
      // read from 3 nodes failed within 1.second
      case NotFound("set1", req)                     => // key set1 does not exist
      //#get-response2
      case GetSuccess("set2", ORSet(elements), None) =>
      case unexpected                                => fail("Unexpected response: " + unexpected)
    }
  }

  "demonstrate get with request context" in {
    import Actor.Receive
    val probe = TestProbe()
    implicit val self = probe.ref
    def sender() = self

    //#get-request-context
    implicit val cluster = Cluster(system)
    val replicator = DistributedData(system).replicator
    val readTwo = ReadFrom(n = 2, timeout = 3.second)

    def receive: Receive = {
      case "get-count" =>
        // incoming request to retrieve current value of the counter
        replicator ! Get("counter1", readTwo, request = Some(sender()))

      case GetSuccess("counter1", PNCounter(value), Some(replyTo: ActorRef)) =>
        replyTo ! value
      case GetFailure("counter1", Some(replyTo: ActorRef)) =>
        replyTo ! -1L
      case NotFound("counter1", Some(replyTo: ActorRef)) =>
        replyTo ! 0L
    }
    //#get-request-context
  }

  "demonstrate subscribe" in {
    import Actor.Receive
    val probe = TestProbe()
    implicit val self = probe.ref
    def sender() = self

    //#subscribe
    val replicator = DistributedData(system).replicator
    // subscribe to changes of the "counter1" value
    replicator ! Subscribe("counter1", self)
    var currentValue = 0L

    def receive: Receive = {
      case Changed("counter1", PNCounter(value)) =>
        currentValue = value
      case "get-count" =>
        // incoming request to retrieve current value of the counter
        sender() ! currentValue
    }
    //#subscribe
  }

  "demonstrate delete" in {
    val probe = TestProbe()
    implicit val self = probe.ref

    //#delete
    val replicator = DistributedData(system).replicator

    replicator ! Delete("counter1", WriteLocal)

    val writeQuorum = WriteQuorum(timeout = 5.seconds)
    replicator ! Delete("set2", writeQuorum)
    //#delete
  }

  "demonstrate PNCounter" in {
    def println(o: Any): Unit = ()
    //#pncounter
    implicit val cluster = Cluster(system)
    val c0 = PNCounter.empty
    val c1 = c0 + 1
    val c2 = c1 + 7
    val c3: PNCounter = c2 - 2
    println(c3.value)
    //#pncounter
  }

  "demonstrate PNCounterMap" in {
    def println(o: Any): Unit = ()
    //#pncountermap
    implicit val cluster = Cluster(system)
    val m0 = PNCounterMap.empty
    val m1 = m0.increment("a", 7)
    val m2 = m1.decrement("a", 2)
    val m3 = m2.increment("b", 1)
    println(m3.get("a"))
    m3.entries.foreach { case (key, value) => println(s"$key -> $value") }
    //#pncountermap
  }

  "demonstrate GSet" in {
    def println(o: Any): Unit = ()
    //#gset
    val s0 = GSet.empty[String]
    val s1 = s0 + "a"
    val s2 = s1 + "b" + "c"
    if (s2.contains("a"))
      println(s2.elements)
    //#gset
  }

  "demonstrate ORSet" in {
    def println(o: Any): Unit = ()
    //#orset
    implicit val cluster = Cluster(system)
    val s0 = ORSet.empty[String]
    val s1 = s0 + "a"
    val s2 = s1 + "b"
    val s3 = s2 - "a"
    println(s3.elements)
    //#orset
  }

  "demonstrate ORMultiMap" in {
    def println(o: Any): Unit = ()
    //#ormultimap
    implicit val cluster = Cluster(system)
    val m0 = ORMultiMap.empty[Int]
    val m1 = m0 + ("a" -> Set(1, 2, 3))
    val m2 = m1.addBinding("a", 4)
    val m3 = m2.removeBinding("a", 2)
    val m4 = m3.addBinding("b", 1)
    println(m4.entries)
    //#ormultimap
  }

  "demonstrate Flag" in {
    def println(o: Any): Unit = ()
    //#flag
    val f0 = Flag.empty
    val f1 = f0.switchOn
    println(f1.enabled)
    //#flag
  }

  "demonstrate LWWRegister" in {
    def println(o: Any): Unit = ()
    //#lwwregister
    implicit val cluster = Cluster(system)
    val r1 = LWWRegister("Hello")
    val r2 = r1.withValue("Hi")
    println(s"${r1.value} by ${r1.updatedBy} at ${r1.timestamp}")
    //#lwwregister
    r2.value should be("Hi")
  }

  "demonstrate LWWRegister with custom clock" in {
    def println(o: Any): Unit = ()
    //#lwwregister-custom-clock
    case class Record(version: Int, name: String, address: String)

    implicit val cluster = Cluster(system)
    implicit val recordClock = new LWWRegister.Clock[Record] {
      override def nextTimestamp(currentTimestamp: Long, value: Record): Long =
        value.version
    }

    val record1 = Record(version = 1, "Alice", "Union Square")
    val r1 = LWWRegister(record1)

    val record2 = Record(version = 2, "Alice", "Madison Square")
    val r2 = LWWRegister(record2)

    val r3 = r1.merge(r2)
    println(r3.value)
    //#lwwregister-custom-clock

    r3.value.address should be("Madison Square")
  }

  "test TwoPhaseSetSerializer" in {
    val s1 = TwoPhaseSet().add("a").add("b").add("c").remove("b")
    s1.elements should be(Set("a", "c"))
    val serializer = SerializationExtension(system).findSerializerFor(s1)
    val blob = serializer.toBinary(s1)
    val s2 = serializer.fromBinary(blob, None)
    s1 should be(s1)
  }

}
