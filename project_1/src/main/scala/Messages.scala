//#testing messages
case object TestAlive
//#testing messages

//#main node messages
case class InitNode(self_node: Node, hyparview_params: HyparviewParams, plumtree_params: PlumTreeParams)
case class Publish(topic: String, message: String)
case class Deliver(topic: String, message: String)
case class Subscribe(topic: String)
case class Unsubscribe(topic: String)
case object PrintNeighbors
case object PrintMessages //TODO: delete this debugg method later
case object CreateReceivedFile
case object Quit
//#main node messages

//#plum tree messages
case class InitPlumTree(self_node: Node, plumtree_params: PlumTreeParams)
case class Broadcast(topic: String, message: String)
case class ReceiveGossip(topic: String, message: String, messageID: Integer, round: Integer, sender: Node)
case class ReceivePrune(sender: Node)
case class ReceiveIHave(messageID: Integer, round: Integer, sender: Node)
case class ReceiveGraft(messageID: Integer, round: Integer, sender: Node)
case class Timer(messageID: Integer)
case class NeighborDown(node: Node)
case class NeighborUp(node: Node)
case class Neighbors(neighbors: Set[Node])
//#plum tree messages messages

//#hyparview messages
case class InitHyParView(self_node: Node, hyparview_params: HyparviewParams)
case class Join(new_node: Node)
case class ForwardJoin(new_node: Node, ttl: Int, sender: Node)
case object UpdatePassiveView
case class Shuffle(exchange_list: List[Node], shuffle_RWL: Int, shuffling_node: Node, sender: Node)
case class ShuffleReply(partial_passive: List[Node])
case class Connect(node: Node)
case class Disconnect(node: Node)
case object GetNeighbors
case class Failed(node: Node)
case class Neighbor(node: Node, priority: Int) // priority: 1 high, 2 low
case class AcceptNeighbor(node: Node)
case class RejectNeighbor(node: Node)
case class HeartBeat(callback: Boolean, contacted_node: Node)
//#testing
case object PrintViews
case class HyparviewMsgCount(count: Int)
//#testing
//#hyparview messages

