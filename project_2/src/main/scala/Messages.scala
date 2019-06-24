import scala.collection.mutable

//#tester
case class InitTester(clients: Set[SocketAddress])

case class AddClient(client: SocketAddress)

case class RemoveClient(client: SocketAddress)

case class AddRep(replica: SocketAddress)

case class RemoveRep(replica: SocketAddress)

case class ClientDone(cputime: Long, latency: Long)

case class ChangeNRounds(n: Int)
//#tester



//# client
case class InitClient(address: SocketAddress, membership: Set[SocketAddress])

case class ClientResponse(value: String)

case class WriteOp(key: String, value: String)

case class ReadOp(key: String)

case class Requests(ops: Set[Op])
//# client



//#testing
case class Last(op: Op, respectiveN: Int, decided: Boolean)
//#testing



//# replica
case class InitReplica(paxosParams: PaxosParams, address: SocketAddress, membership: Set[SocketAddress])

case class Write(sender: SocketAddress, key: String, value: String)

case class Read(sender: SocketAddress, key: String)

case class AddReplica(sender: SocketAddress, newReplica: SocketAddress)

case class AddReplicaResponse(n: Int, pp: PaxosParams, opId: String)

case class RemoveReplica(sender: SocketAddress, removedReplica: SocketAddress)

case class Decide(n: Int, op: Op)

case class Sync(sender: SocketAddress, missedOps: Set[Int])

case class SyncResponse(sender: SocketAddress, ops: mutable.HashMap[Int, Op])

case class Crash(sender: SocketAddress)
//# replica



//# paxos
case class InitPaxos(myself: SocketAddress, membership: Set[SocketAddress], paxosParams: PaxosParams, sn: Int)

case class AddToMembership(replica: SocketAddress)

case class RemoveFromMembership(replica: SocketAddress)

case class Propose(n: Int, op: Op)

case class RetryPropose(n: Int, op: Op)

case class Prepare(sender: SocketAddress, n: Int, sn: Int, op: Op)

case class PrepareOK(n: Int, sn: Int, op: Op)

case class Accept(n: Int, sn: Int, op: Op)

case class AcceptOK(n: Int, sn: Int, op: Op)
//# paxos