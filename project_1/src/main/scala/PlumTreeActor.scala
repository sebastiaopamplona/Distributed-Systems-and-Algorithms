import java.io.{BufferedWriter, File, FileWriter}
import akka.actor.{Actor, ActorLogging, ActorSelection, Timers}
import scala.concurrent.duration._
import language.postfixOps

class PlumTreeActor extends Actor with ActorLogging with Timers {

    var mIDToTopicAndMessage = Map.empty[Integer, (String, String)] // mID to (Topic, Message) mapping
    var missing = Set.empty[(Integer, Node, Integer)] // set of (mID, process, round) tuples
    var eagerPushPeers = Set.empty[Node] // set of eager push peers
    var lazyPushPeers = Set.empty[Node] // set of lazy push peers
    var self_node: Node = _ // properties of this node
    var params: PlumTreeParams = _ // constants (eg. timeout)

    // Variables only used for test purposes
    var eagerCounter: Int = 0
    var lazyCounter: Int = 0

    override def receive: Receive = {

        case InitPlumTree(node, pp) =>
            this.self_node = node
            params = pp
            context.parent ! GetNeighbors

        case Broadcast(t, m) =>
            val mID = new Integer((m + self_node.toString() + System.currentTimeMillis().toString).hashCode)
            eagerPush(t, m, mID, 0, self_node)
            lazyPush(mID, 0, self_node)
            context.parent ! Deliver(t, m)
            mIDToTopicAndMessage += (mID -> (t, m))

        case ReceiveGossip(t, m, mID, round, sender) =>
            eagerCounter += 1
            if (!mIDToTopicAndMessage.contains(mID)) {
                mIDToTopicAndMessage += (mID -> (t, m))
                context.parent ! Deliver(t, m)
                timers.cancel(Timer(mID))
                for ((id, n, r) <- missing if id == mID) {
                    val tuple = (id, n, r)
                    missing -= tuple
                }
                eagerPush(t, m, mID, round + 1, sender)
                lazyPush(mID, round + 1, sender)
                eagerPushPeers += sender
                lazyPushPeers -= sender
                optimization(mID, round, sender)
            }
            else {
                eagerPushPeers -= sender
                lazyPushPeers += sender
                actorAddress(sender) ! ReceivePrune(self_node)
            }

        case ReceivePrune(sender) =>
            eagerPushPeers -= sender
            lazyPushPeers += sender

        case ReceiveIHave(mID, round, sender) =>
            lazyCounter += 1
            if (!mIDToTopicAndMessage.contains(mID)) {
                if (!timers.isTimerActive(Timer(mID))) {
                    timers.startSingleTimer(Timer(mID), Timer(mID), params.timeout1 / 1 milliseconds)
                }
                val tuple = (mID, sender, round)
                missing += tuple
            }

        case Timer(mID) =>
            val (_, node, round) = removeFirstAnnouncement(mID)
            if (node != null) {
                timers.startSingleTimer(Timer(mID), Timer(mID), params.timeout2 / 1 milliseconds)
                eagerPushPeers += node
                lazyPushPeers -= node
                actorAddress(node) ! ReceiveGraft(mID, round, self_node)
            }

        case ReceiveGraft(mID, round, sender) =>
            if(mID != null) lazyCounter += 1
            eagerPushPeers += sender
            lazyPushPeers -= sender
            if (mIDToTopicAndMessage.contains(mID)) {
                val (t, m) = mIDToTopicAndMessage.apply(mID)
                actorAddress(sender) ! ReceiveGossip(t, m, mID, round, self_node)
            }

        case NeighborDown(node) =>
            eagerPushPeers -= node
            lazyPushPeers -= node
            for ((i, n, r) <- missing if n == node) {
                val tuple = (i, n, r)
                missing -= tuple
            }

        case NeighborUp(node) =>
            eagerPushPeers += node

        case Neighbors(nodes) =>
            val (l, _) = nodes.splitAt(scala.math.min(params.fanout, nodes.size))
            eagerPushPeers = l

        case CreateReceivedFile =>
            val bw = new BufferedWriter(new FileWriter(new File(params.outputPath + self_node.port + ".txt")))
            bw.write("" + eagerCounter + " " + lazyCounter)
            bw.close()
            println("[SUCCESS] Plumtree created text file.")
    }

    // Procedures
    /**
      * Sends a message for each neighbor in the eager push set, with the full content of the message.
      * We do not send the message to the process that sent us the message.
      * @param t topic of the message to be send.
      * @param m content of the message to be send.
      * @param mID unique identifier of the message to be send.
      * @param round current round of the message to be send.
      * @param sender process that sent us the message.
      */
    def eagerPush(t: String, m: String, mID: Integer, round: Integer, sender: Node): Unit = {
        for (p <- eagerPushPeers if p != sender)
            actorAddress(p) ! ReceiveGossip(t, m, mID, round, self_node)
    }

    /**
      * Sends a message for each neighbor in the lazy push set, with the unique identifier of the message.
      * We do not send the message to the process that sent us the message.
      * @param mID unique identifier of the message to be send.
      * @param round current round of the message to be send.
      * @param sender process that sent us the message.
      */
    def lazyPush(mID: Integer, round: Integer, sender: Node): Unit = {
        for (p <- lazyPushPeers if p != sender)
            actorAddress(p) ! ReceiveIHave(mID, round, self_node)
    }

    /**
      * Optimizes the tree by replacing non-optimal eager peers by better lazy peers.
      * @param mID unique identifier of the message to be send.
      * @param round current round of the message to be send.
      * @param sender process that sent us the message.
      */
    def optimization(mID: Integer, round: Integer, sender: Node): Unit = {
        for ((id, n, r) <- missing if id == mID) {
            if (r < round && round - r > params.threshold) {
                actorAddress(n) ! ReceiveGraft(null, r, self_node)
                actorAddress(sender) ! ReceivePrune(self_node)
            }
        }
    }

    // Utilities
    /**
      * Removes the first instance of a tuple from missing with id = mID.
      * @param mID unique identifier of the message.
      * @return returns a tuple of (id, node, round) if id==mID. Returns null otherwise.
      */
    def removeFirstAnnouncement(mID: Integer): (Integer, Node, Integer) = {
        for ((id, node, round) <- missing if id == mID) {
            val tuple = (id, node, round)
            missing -= tuple
            return tuple
        }
        (mID, null, new Integer(0))
    }

    /**
      * Returns an ActorSelection, given a node (tuple with hostname and port).
      * @param node node used to create the ActorSelection.
      * @return an ActorSelection
      */
    def actorAddress(node: Node): ActorSelection = {
        context.actorSelection("akka.tcp://node_system@" + node.toString + "/user/node/plumtree")
    }
}