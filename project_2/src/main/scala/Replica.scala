import java.io.{File}

import akka.actor.{Actor, ActorRef, ActorSelection, ActorSystem, Props}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.io.StdIn
import scala.util.Random

class Replica extends Actor {
    // General
    var myself: SocketAddress = _
    var paxos: ActorRef = _
    var membership: Set[SocketAddress] = Set.empty

    // Replication
    var storage: mutable.HashMap[String, String] = mutable.HashMap.empty
    var opSequence: ArrayBuffer[Op] = ArrayBuffer.empty
    var ops: mutable.Queue[Op] = mutable.Queue.empty
    var priorityOps: mutable.Queue[Op] = mutable.Queue.empty

    var waiting: Boolean = false
    var pendingResponse: mutable.HashMap[String, SocketAddress] = mutable.HashMap.empty
    var missedOps: Set[Int] = Set.empty

    var leftToSync: Set[SocketAddress] = Set.empty
    var requestCount: Int = 0

    var paxosParams: PaxosParams = _

    var checkpoint: Int = 0

    override def receive: Receive = {
        case InitReplica(pp, sa, m) =>
            myself = sa
            membership = m
            paxosParams = pp
            leftToSync = membership - myself
            if (m.contains(myself)) {
                paxos = context.actorOf(Props[Paxos], "paxos")
                paxos ! InitPaxos(myself, membership, pp, myself.port - 2549)
            }
            else
                replicaASC(randomMember(membership)) ! AddReplica(myself, myself)


        case Write(sender, key, value) =>
            requestCount += 1
            val op = Op("write", Array(key, value), myself.toString() + requestCount)
            pendingResponse.update(op.opId, sender)
            ops.enqueue(op)

            if (!waiting) {
                paxos ! Propose(opSequence.size, ops.head)
                waiting = true
            }


        case Read(sender, key) =>
            clientASC(sender) ! ClientResponse("OK. value: [" + storage(key) + "]")


        case AddReplica(sender, newReplica) =>
            requestCount += 1
            val op = Op("addReplica", Array(newReplica), myself.toString() + requestCount)
            pendingResponse.update(op.opId, sender)
            priorityOps.enqueue(op)
            if (!waiting) {
                waiting = true
                paxos ! Propose(opSequence.size, priorityOps.head)
            }


        case AddReplicaResponse(n, pp, opId) =>
            membership += myself
            paxos = context.actorOf(Props[Paxos], "paxos")
            paxos ! InitPaxos(myself, membership, pp, myself.port - 2549)

            checkpoint = 0
            for (i <- 0 until n - 1) {
                opSequence += null
                missedOps += i
            }

            opSequence += Op("addReplica", Array(myself), opId)

            replicaASC(randomMember(membership - myself)) ! Sync(myself, missedOps)


        case RemoveReplica(sender, removedReplica) =>
            if (membership.contains(removedReplica)) {
                requestCount += 1
                val op = Op("removeReplica", Array(removedReplica), myself.toString() + requestCount)
                pendingResponse.update(op.opId, sender)
                priorityOps.enqueue(op)
                if (!waiting)
                    paxos ! Propose(opSequence.size, priorityOps.head)
            }


        case Decide(n, op) =>
            if (n == opSequence.size) {
                opSequence += op
                executeOp(op)

                if (priorityOps.nonEmpty)
                    paxos ! Propose(opSequence.size, priorityOps.head)
                else if (ops.nonEmpty)
                    paxos ! Propose(opSequence.size, ops.head)

            } else if (n > opSequence.size) {
                checkpoint = opSequence.size
                for (i <- opSequence.size until n) {
                    opSequence += null
                    missedOps += i
                }
                opSequence += op

                replicaASC(randomMember(membership - myself)) ! Sync(myself, missedOps)
            }


        case Sync(sender, missed) =>
            val synced: mutable.HashMap[Int, Op] = mutable.HashMap.empty
            missed.filter(_ < opSequence.size).foreach(i => {
                if (opSequence(i) != null)
                    synced.update(i, opSequence(i))
            })

            replicaASC(sender) ! SyncResponse(myself, synced)


        case SyncResponse(sender, synced) =>
            leftToSync -= sender
            synced.foreach(missed => {
                val syncedPos = missed._1
                val syncedOp = missed._2
                missedOps -= syncedPos
                opSequence(syncedPos) = syncedOp
            })

            if (missedOps.isEmpty) {
                leftToSync = membership - myself
                for (i <- checkpoint until opSequence.size)
                    executeOp(opSequence(i))

                checkpoint = opSequence.size
                if (priorityOps.nonEmpty)
                    paxos ! Propose(opSequence.size, priorityOps.head)
                else if (ops.nonEmpty)
                    paxos ! Propose(opSequence.size, ops.head)
            } else{
                if(leftToSync.isEmpty)
                    leftToSync = membership - myself
                else
                    replicaASC(randomMember(leftToSync)) ! Sync(myself, missedOps)

            }

        case Crash(sender) =>
            self ! RemoveReplica(myself, sender)

        //#commands
        case "m" =>
            println(membership)
            print(">")

        case "all" =>
            if(storage.nonEmpty){
                storage.foreach(s => println(s.toString()))
            } else
                println("[]")

            print(">")

        case "s" =>
            if(opSequence.nonEmpty){
                for (i <- opSequence.indices)
                    println("[" + i + "]" + opSequence(i))
            } else
                println("[]")

            print(">")

        case "l" =>
            println("I got " + ops.size + " ops left to propose.")
            print(">")

        case "la" =>
            paxos ! "la"

        case Last(op, n, decided) =>
            println("Last operation proposed: " + op + " for position [" + n + "] (decided = " + decided + ")")
            print(">")

        case "crash" =>
            context.stop(self)
            println("[OK] Replica dead.")
        //#commands

        case x: Any =>
            println("Got unexpected msg <" + x + "> of type " + x.getClass)

    }

    // Fail-Stop
    override def postStop(): Unit = {
        replicaASC(randomMember(membership - myself)) ! RemoveReplica(myself, myself)
    }

    // Procedures

    /** Executes an operation that is stored in the operation sequence (write, addReplica or removeReplica).
      * After executing, returns to the requester the result of the operation.
      *
      * @param op operation to execute (write, addReplica or removeReplica)
      */
    def executeOp(op: Op): Unit = {
        op.op match {
            case "write" =>
                var old = ""
                try {
                    old = storage(op.args(0).asInstanceOf[String])
                } catch {
                    case _: Exception =>
                }

                storage.update(op.args(0).asInstanceOf[String], op.args(1).asInstanceOf[String])

                val client = pendingResponse.get(op.opId)
                if (client.isDefined) {
                    pendingResponse.remove(op.opId)
                    ops.dequeue()
                    waiting = false

                    clientASC(client.get) ! ClientResponse("OK. old write: [" + old + "]")
                }

            case "addReplica" =>
                val newReplica = op.args(0).asInstanceOf[SocketAddress]
                membership += newReplica
                paxos ! AddToMembership(newReplica)

                val replica = pendingResponse.get(op.opId)
                if (replica.isDefined) {
                    pendingResponse.remove(op.opId)
                    priorityOps.dequeue()
                    waiting = false

                    replicaASC(replica.get) ! AddReplicaResponse(opSequence.size, paxosParams, op.opId)
                }

            case "removeReplica" =>
                val removedReplica = op.args(0).asInstanceOf[SocketAddress]
                if (membership.contains(removedReplica)) {
                    membership -= removedReplica
                    paxos ! RemoveFromMembership(removedReplica)

                    if (pendingResponse.get(op.opId).isDefined) {
                        pendingResponse.remove(op.opId)
                        priorityOps.dequeue()
                        waiting = false
                    }
                }

        }

    }

    def randomMember(membership: Set[SocketAddress]): SocketAddress = {
        Random.shuffle((membership - myself).toList).drop(membership.size - 1).head
    }

    def replicaASC(address: SocketAddress): ActorSelection = {
        context.actorSelection("akka.tcp://replicaSystem@" + address.toString + "/user/replica")
    }

    def clientASC(address: SocketAddress): ActorSelection = {
        context.actorSelection("akka.tcp://clientSystem@" + address.toString + "/user/client")
    }

}

object Replica {
    def help(): Unit ={
        println("\tall - prints the state machine")
        println("\tm - prints the current membership")
        println("\ts - prints the operation sequence")
        println("\tl - prints the number of operations left to propose")
        println("\tla - prints the last operation proposed")
        println("\tcrash - crashes the current replica")
        println("\tquit - quits")
        println("\thelp - list commands")
    }

    def main(args: Array[String]): Unit = {
        val host = args(0)
        val port = args(1).toInt

        // Setup application.conf file for address and port of the ActorSystem
        val default_config = ConfigFactory.parseFile(new File("./src/resources/application.conf"))
        var config = default_config.withValue("akka.remote.netty.tcp.hostname", ConfigValueFactory.fromAnyRef(host))
        config = config.withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(port))

        // Extract HyParView params
        val paxosParams = PaxosParams(config.getInt("paxos.prepare-ok-timeout"),
            config.getInt("paxos.accept-ok-timeout"))


        // TODO: this is super hardcoded just to keep doing the project
        val sa1 = config.getString("membership.initial1").split(":")
        val sa2 = config.getString("membership.initial2").split(":")
        val sa3 = config.getString("membership.initial3").split(":")
        val initialMembership = Set(SocketAddress(sa1(0), sa1(1).toInt),
                                    SocketAddress(sa2(0), sa2(1).toInt),
                                    SocketAddress(sa3(0), sa3(1).toInt))

        // Setup replica actor
        val system = ActorSystem("replicaSystem", config)
        val replica = system.actorOf(Props[Replica], "replica")
        val address = SocketAddress(host, port)

        replica ! InitReplica(paxosParams, address, initialMembership)

        help()
        print(">")
        var cmd: String = ""
        while (cmd != "quit") {
            cmd = StdIn.readLine().toLowerCase
            cmd match {
                case "all" =>
                    replica ! "all"

                case "m" =>
                    replica ! "m"

                case "s" =>
                    replica ! "s"

                case "l" =>
                    replica ! "l"

                case "la" =>
                    replica ! "la"

                case "quit" =>
                    system.terminate()

                case "crash" =>
                    replica ! "crash"

                case "help" =>
                    help()

                case unknown: String =>
                    println("Unknown command: " + unknown)
            }
        }

    }


}
