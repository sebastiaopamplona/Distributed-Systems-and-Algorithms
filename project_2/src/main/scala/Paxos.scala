import akka.actor.{Actor, ActorSelection, Timers}

import scala.collection.mutable
import scala.concurrent.duration._

class Paxos extends Actor with Timers {
    // General
    var myself: SocketAddress = _
    var membership: Set[SocketAddress] = Set.empty
    var decided: mutable.HashMap[Int, Boolean] = mutable.HashMap.empty

    // Proposer
    var initialSequenceNumber: Int = 0
    var sequenceNumber: Int = 0
    var prepareOKCount: mutable.HashMap[String, Int] = mutable.HashMap.empty
    var prepareOKTimeOut: Int = 0
    var acceptOKTimeOut: Int = 0
    val PREPAREOK_KEY = "prepareOK"
    val ACCEPTOK_KEY = "acceptOK"

    // Acceptor
    var promise: mutable.HashMap[Int, Int] = mutable.HashMap.empty

    // Learner
    var acceptOKCount: mutable.HashMap[String, Int] = mutable.HashMap.empty

    override def receive: Receive = {

        case InitPaxos(address, m, pp, sn) =>
            myself = address
            membership = m
            prepareOKTimeOut = pp.prepareOkTimeout
            acceptOKTimeOut = pp.acceptOkTimeout
            initialSequenceNumber = sn
            sequenceNumber = sn

        // as Proposer
        case Propose(n, operation) =>
            membership.foreach(m => actorSelectionConstructor(m) ! Prepare(myself, n, sequenceNumber, operation))
            timers.startSingleTimer(PREPAREOK_KEY + "_" + n, RetryPropose(n, operation), prepareOKTimeOut milliseconds)
            checkDecidedAndPromise(n, d = true, p = true)

            prepareOKCount.update("n_" + n + "sn_ " + sequenceNumber, 0)

        // as Proposer
        case RetryPropose(n, operation) =>
            checkDecidedAndPromise(n, d = true, p = true)

            if (!decided(n)) {
                sequenceNumber = promise(n) + 1
                self ! Propose(n, operation)
            }

        // as Acceptor
        case Prepare(sender, n, sn, operation) =>
            checkDecidedAndPromise(n, d = true, p = true)

            if (sn > promise(n)) {
                promise.update(n, sn)
                actorSelectionConstructor(sender) ! PrepareOK(n, sn, operation)
            }

        // as Proposer
        case PrepareOK(n, sn, operation) =>
            checkDecidedAndPromise(n, d = true, p = false)

            if (prepareOKCount("n_" + n + "sn_ " + sn) <= membership.size / 2){
                prepareOKCount.update("n_" + n + "sn_ " + sn, prepareOKCount("n_" + n + "sn_ " + sn) + 1)
                if (prepareOKCount("n_" + n + "sn_ " + sn) > membership.size / 2){
                    timers.cancel(PREPAREOK_KEY + "_" + n)
                    membership.foreach(m => actorSelectionConstructor(m) ! Accept(n, sn, operation))
                    timers.startSingleTimer(ACCEPTOK_KEY + "_" + n, RetryPropose(n, operation), acceptOKTimeOut milliseconds)
                }
            }

        // as Acceptor
        case Accept(n, sn, operation) =>
            timers.cancel(PREPAREOK_KEY + "_" + n)
            checkDecidedAndPromise(n, d = true, p = true)

            if (sn >= promise(n))
                (membership - myself).foreach(m => actorSelectionConstructor(m) ! AcceptOK(n, sn, operation))


        // as Acceptor
        case AcceptOK(n, sn, operation) =>
            checkDecidedAndPromise(n, d = true, p = true)

            if (sn >= promise(n) && !decided(n)) {
                try {
                    acceptOKCount("n_" + n + "sn_ " + sn)
                } catch {
                    case _: Exception =>
                        acceptOKCount.update("n_" + n + "sn_ " + sn, 0)
                }

                if (acceptOKCount("n_" + n + "sn_ " + sn) <= membership.size / 2) {
                    acceptOKCount.update("n_" + n + "sn_ " + sn, acceptOKCount("n_" + n + "sn_ " + sn) + 1)
                    if(operation.op != "removeReplica"){
                        if (acceptOKCount("n_" + n + "sn_ " + sn) > membership.size / 2)
                            membership.foreach(m => actorSelectionConstructor(m) ! Decide(n, operation))

                    } else if (acceptOKCount("n_" + n + "sn_ " + sn) > (membership.size / 2) - 1)
                        membership.foreach(m => actorSelectionConstructor(m) ! Decide(n, operation))

                }
            }

        // as Learner
        case Decide(n, operation) =>
            checkDecidedAndPromise(n, d = true, p = false)
            if (!decided(n)) {
                timers.cancel(PREPAREOK_KEY + "_" + n)
                timers.cancel(ACCEPTOK_KEY + "_" + n)
                decided.update(n, true)
                sequenceNumber = initialSequenceNumber
                context.parent ! Decide(n, operation)
            }


        case AddToMembership(replica) =>
            membership += replica

        case RemoveFromMembership(replica) =>
            membership -= replica

        case x: Any =>
            println("Got unexpected msg <" + x + "> of type " + x.getClass)
    }

    // checks if the variables in the hashmap are initialized; initializes if they are not
    def checkDecidedAndPromise(n: Int, d: Boolean, p: Boolean): Unit = {
        if (d)
            try { decided(n) } catch { case _: Exception => decided.update(n, false) }
        if (p)
            try { promise(n) } catch { case _: Exception => promise.update(n, 0) }
    }

    def actorSelectionConstructor(address: SocketAddress): ActorSelection = {
        context.actorSelection("akka.tcp://replicaSystem@" + address.toString + "/user/replica/paxos")
    }

}
