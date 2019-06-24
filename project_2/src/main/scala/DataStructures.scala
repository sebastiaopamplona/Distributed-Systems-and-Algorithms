case class Op(op: String, args: Array[_], opId: String) {
    override def toString(): String = {
        op match {
            case "write" =>
                "write(" + args(0).asInstanceOf[String] + ", " + args(1).asInstanceOf[String] + ")"
            case "addReplica" =>
                "addReplica(" + args(0).asInstanceOf[SocketAddress].toString() + ")"
            case "removeReplica" =>
                "removeReplica(" + args(0).asInstanceOf[SocketAddress].toString() + ")"
        }
    }
}

case class PaxosParams(prepareOkTimeout: Int, acceptOkTimeout: Int)

case class SocketAddress(host: String, port: Int) {
    override def toString(): String = host + ":" + port
}