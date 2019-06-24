import java.io.{BufferedWriter, File, FileWriter}

import akka.actor.{Actor, ActorIdentity, ActorSelection, Identify, Timers}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

class HyParViewActor extends Actor with Timers {
    var myself: Node = _
    var hyparview_params: HyparviewParams = _
    var active_view = Set.empty[Node]
    var passive_view = Set.empty[Node]
    var last_exchange_list = List.empty[Node]
    var already_rejected = Set.empty[Node]
    var fanout: Int = 0
    var counter: Int = 0

    override def receive: Receive = {
        case InitHyParView(s, hp) =>
            myself = s
            hyparview_params = hp
            fanout = Math.round(Math.log(hyparview_params.num_nodes)).asInstanceOf[Int]
            //TODO: delete this comment
            //println(hyparview_params.toString())

            //TODO: delete this timer
            //timers.startPeriodicTimer("print_views", PrintViews, 10.seconds)

            timers.startPeriodicTimer("update_passive", UpdatePassiveView, hyparview_params.passive_update_time.seconds)

            addNodeActiveView(hyparview_params.contact_node)
            actorSelectionConstructor(hyparview_params.contact_node) ! Join(myself)
            counter += 1


        case Join(new_node) =>
            addNodeActiveView(new_node)
            val targets = active_view - new_node

            targets.foreach(n => {
                // Suspect node failure
                actorSelectionConstructor(n) ! Identify(HeartBeat(callback = false, n))
                actorSelectionConstructor(n) ! ForwardJoin(new_node, hyparview_params.active_RWL, myself)
                counter += 2
            })

        case ForwardJoin(new_node, ttl, sender) =>
            if (ttl == 0 || active_view.size == 1) {
                addNodeActiveView(new_node)
                actorSelectionConstructor(new_node) ! Connect(myself)
                counter += 1
            }
            else {
                if (ttl == hyparview_params.passive_RWL)
                    addNodePassiveView(new_node)

                val n = randomNeighbor(sender)

                // Suspect node failure
                actorSelectionConstructor(n) ! Identify(HeartBeat(callback = false, n))
                actorSelectionConstructor(n) ! ForwardJoin(new_node, ttl - 1, myself)
                counter += 2
            }


        case UpdatePassiveView =>
            if (active_view.nonEmpty) {
                // Create exchange list with ka from active_view, kp from passive_view and myself
                var exchange_list =
                    randomSelection(hyparview_params.shuffle_ka, active_view) :::
                        randomSelection(hyparview_params.shuffle_kp, passive_view)
                exchange_list ::= myself

                // List of nodes to remove first, if when updating the passive view, it is full
                last_exchange_list = exchange_list

                val n = randomNeighbor(null)

                // Suspect node failure
                actorSelectionConstructor(n) ! Identify(HeartBeat(false, n))
                actorSelectionConstructor(n) ! Shuffle(exchange_list, hyparview_params.shuffle_RWL, myself, myself)
                counter += 2
            }


        case Shuffle(exchange_list, shuffle_RWL, shuffling_node, sender) =>
            if (shuffle_RWL - 1 > 0 && active_view.size > 1) {
                val n = randomNeighbor(sender)

                // Suspect node failure
                actorSelectionConstructor(n) ! Identify(HeartBeat(false, n))
                actorSelectionConstructor(n) ! Shuffle(exchange_list, shuffle_RWL - 1, shuffling_node, myself)
                counter += 2
            }

            else {
                val partial_passive = passive_view.toList.drop(passive_view.size - exchange_list.size)

                // List of nodes to remove first, if when updating the passive view, it is full
                last_exchange_list = partial_passive

                integrateIntoPassiveView(exchange_list)

                // No need to suspect node failure, because shuffling_node is most likely not on the active view
                actorSelectionConstructor(shuffling_node) ! ShuffleReply(partial_passive)
                counter += 1
            }


        case Neighbor(node, priority) =>
            if (priority == 1 || !isFull(active_view, true)) {
                addNodeActiveView(node)
                actorSelectionConstructor(node) ! AcceptNeighbor(myself)
            } else
                actorSelectionConstructor(node) ! RejectNeighbor(myself)

            counter += 1


        case AcceptNeighbor(node) =>
            passive_view -= node
            already_rejected = Set.empty[Node]
            addNodeActiveView(node)

        case RejectNeighbor(contacted_node) =>
            pickFromPassive(contacted_node, false, true)

        case GetNeighbors =>
            context.parent ! Neighbors(active_view)

        case ShuffleReply(partial_passive) =>
            integrateIntoPassiveView(partial_passive)

        case Connect(node) =>
            addNodeActiveView(node)

        case Disconnect(node) =>
            if (active_view.contains(node)) {
                active_view -= node
                addNodePassiveView(node)
            }

            pickFromPassive(null, false, false)

        case Failed(node) =>
            if (active_view.contains(node))
                active_view -= node

            pickFromPassive(null, false, false)

        case resp: ActorIdentity =>
            val heartbeat = resp.correlationId.asInstanceOf[HeartBeat]

            if (heartbeat.callback) {
                if (resp.ref.isEmpty)
                    pickFromPassive(heartbeat.contacted_node, false, false)
                else
                    pickFromPassive(heartbeat.contacted_node, true, false)

            } else {
                if (resp.ref.isEmpty) {
                    if (active_view.contains(heartbeat.contacted_node))
                        active_view -= heartbeat.contacted_node

                    pickFromPassive(heartbeat.contacted_node, false, false)
                }
            }

        case PrintViews =>
            print_views()
        //TODO: #testing, delete eventually

        case CreateReceivedFile =>
            val bw = new BufferedWriter(new FileWriter(new File(hyparview_params.outputPath + myself.port + ".txt")))
            bw.write("" + counter)
            bw.close()
            println("[SUCCESS] HyParView created text file.")

        case "getHyparviewMsgCount" =>
            context.parent ! HyparviewMsgCount(counter)

        case msg: Any =>
            println("Got unexpected message: " + msg)
    }

    override def postStop(): Unit = {
        active_view.foreach(n => {
            actorSelectionConstructor(n) ! Failed(myself)
            counter += 1
        })
    }

    // Procedures

    /** Selects a given number of nodes, at random, from a set of nodes.
      *
      * @param n     number of nodes to be selected.
      * @param nodes set of nodes to select from.
      * @return
      */
    def randomSelection(n: Int, nodes: Set[Node]): List[Node] = {
        Random.shuffle(nodes.toList).drop(nodes.size - n)
    }

    /** Selects a random node from the active view, possibly avoiding one of the members (normally, the sender of a msg).
      *
      * @param sender node to be avoided.
      * @return
      */
    def randomNeighbor(sender: Node): Node = {
        if (sender == null)
            randomSelection(1, active_view).head

        else
            randomSelection(1, active_view - sender).head

    }

    /** Drops a random member from the active view. Sends a DISCONNECT request to the dropped neighbor, in order to
      * preserve link symmetry.
      *
      */
    def dropRandomFromActiveView(): Unit = {
        val n = randomSelection(1, active_view).head
        active_view -= n
        addNodePassiveView(n)

        // No need to suspect node failure, because the node was already removed from the active view
        actorSelectionConstructor(n) ! Disconnect(myself)
        counter += 1
    }

    /** Adds a node to the active view.
      *
      * @param node node to be added.
      */
    def addNodeActiveView(node: Node): Unit = {
        if (node != myself && !active_view.contains(node)) {
            if (isFull(active_view, true))
                dropRandomFromActiveView()

            active_view += node
            context.parent ! NeighborUp(node)
            counter += 1
        }
    }

    /** Adds a node to the passive view.
      *
      * @param node node to be added to the passive view.
      */
    def addNodePassiveView(node: Node): Unit = {
        if (node != myself && !active_view.contains(node) && !passive_view.contains(node)) {
            if (isFull(passive_view, false))
                passive_view -= randomSelection(1, passive_view).head

            passive_view += node
        }
    }

    /** Checks if a view is full.
      *
      * @param view   passive or active view.
      * @param active flag to know if its the active view.
      * @return
      */
    def isFull(view: Set[Node], active: Boolean): Boolean = {
        if (active)
            view.size == fanout + 1

        else
            view.size == hyparview_params.passive_view_max_size

    }

    /** Returns an ActorSelection, given a node (tuple with hostname and port).
      *
      * @param node node used to create the ActorSelection.
      * @return
      */
    def actorSelectionConstructor(node: Node): ActorSelection = {
        context.actorSelection("akka.tcp://node_system@" + node.toString + "/user/node/hyparview")
    }

    /** Integrates a set of nodes into the passive view, avoiding members of the current active and passive view, as well
      * as the node itself ('myself'). If the passive view is full, the first nodes to be removed are the ones sent on
      * the last SHUFFLE request.
      *
      * @param to_integrate set of nodes to be integrated into the passive view.
      */
    def integrateIntoPassiveView(to_integrate: List[Node]): Unit = {
        val to_integrate_parsed = to_integrate.filter(!active_view.contains(_))
            .filter(!passive_view.contains(_))
            .filter(_ != myself)

        val intersection = mutable.Queue(passive_view.toList.intersect(last_exchange_list): _*)
        for (node <- to_integrate_parsed) {
            if (isFull(passive_view, false)) {
                if (intersection.nonEmpty)
                    passive_view -= intersection.dequeue()
                else
                    passive_view -= randomSelection(1, passive_view).head


            }

            addNodePassiveView(node)
        }

    }

    /** Tries to reconstruct the active view, by attempting to promote a node from the passive view to the active view,
      * after a node failure. After detecting the node failure, this method can be called in 4 different situations:
      * 1) first attempt at establishing a connection with a member of the passive view
      * (failed_node, contacted_passive_neighbor = null, proceed = false, rejected = false)
      *
      * 2) the attempt of establishing a connection with a member of the passive view was not successful
      * (failed_node, contacted_passive_neighbor, proceed = false, rejected = false)
      *
      * 3) the attempt of establishing a connection with a member of the passive view was successful
      * (failed_node, contacted_passive_neighbor, proceed = true, rejected = false)
      *
      * 4) the attempt of establishing a connection with a member of the passive view was successful, but the NEIGHBOR
      * request was rejected
      * (failed_node, contacted_passive_neighbor, proceed = false, rejected = true)
      *
      * @param contacted_passive_neighbor last contacted passive view member.
      * @param proceed                    flag to proceed with the callback.
      * @param rejected                   flag to know if the NEIGHBOR request was rejected.
      */
    def pickFromPassive(contacted_passive_neighbor: Node, proceed: Boolean, rejected: Boolean): Unit = {
        var passive_neighbor: Node = null

        if (!proceed) {
            if (rejected) {
                already_rejected += contacted_passive_neighbor
                if (passive_view.exists(!already_rejected.contains(_))) {
                    passive_neighbor = randomSelection(1, passive_view.filter(!already_rejected.contains(_))).head
                    actorSelectionConstructor(passive_neighbor) ! Identify(HeartBeat(true, passive_neighbor))
                } else
                    already_rejected = Set.empty[Node]

            } else {
                if (contacted_passive_neighbor != null)
                    passive_view -= contacted_passive_neighbor

                if (passive_view.nonEmpty) {
                    passive_neighbor = randomSelection(1, passive_view).head
                    actorSelectionConstructor(passive_neighbor) ! Identify(HeartBeat(true, passive_neighbor))
                }
            }
        }
        else
            actorSelectionConstructor(contacted_passive_neighbor) ! Neighbor(myself, if (active_view.isEmpty) 1 else 2)


        counter += 1

    }

    //TODO: bellow is just testing, to be deleted eventually
    def print_views(): Unit = {
        println("-------------------------------")
        println("ACTIVE : " + active_view)
        println("PASSIVE : " + passive_view)
        println("-------------------------------")
    }
}
