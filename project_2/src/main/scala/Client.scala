import java.io.File

import akka.actor.{Actor, ActorSelection, ActorSystem, Props}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

import scala.io.StdIn
import scala.util.Random

class Client extends Actor {
    var myself: SocketAddress = _
    var membership: Set[SocketAddress] = Set.empty

    var requests: Set[Op] = Set.empty
    var start: Long = 0

    var totalLatency: Long = 0
    var tRequest: Long = 0

    override def receive: Receive = {
        case InitClient(sa, m) =>
            myself = sa
            membership = m

        case WriteOp(k, v) =>
            serverConnection() ! Write(myself, k, v)


        case ReadOp(k) =>
            serverConnection() ! Read(myself, k)


        case ClientResponse(response) =>
            val latency = System.currentTimeMillis() - tRequest
            totalLatency += latency
            println(response)
            requests -= requests.head
            if (requests.nonEmpty)
                request(requests.head)
            else {
                testerASC() ! ClientDone(System.currentTimeMillis() - start, totalLatency)
                totalLatency = 0
                println("Done.")
                print(">")
            }


        case Requests(ops) =>
            requests = ops
            start = System.currentTimeMillis()
            request(ops.head)

        case AddRep(r) =>
            if(!membership.contains(r)){
                membership += r
                println("[OK] Replica " + r + " added.")
            } else
                println("[ERROR] Replica " + r + " is already online.")

        case RemoveRep(r) =>
            if(membership.contains(r)){
                membership -= r
                println("[OK] Replica " + r + " removed.")
            } else
                println("[ERROR] Replica " + r + " was not online.")

        case "m" =>
            println(membership)
            println("[OK]")

        case x: Any =>
            println("Got unexpected msg <" + x + "> of type " + x.getClass)
    }

    def testerASC(): ActorSelection = {
        context.actorSelection("akka.tcp://testerSystem@127.0.0.1:2530/user/tester")
    }

    def request(op: Op): Unit = {
        tRequest = System.currentTimeMillis()
        serverConnection() ! Write(myself, op.args(0).asInstanceOf[String], op.args(1).asInstanceOf[String])
    }

    def serverConnection(): ActorSelection = {
        val replica = Random.shuffle(membership.toList).drop(membership.size - 1).head

        context.actorSelection("akka.tcp://replicaSystem@" + replica.toString + "/user/replica")
    }
}

object Client {
    def help(): Unit = {
        println("\twrite(<key>, <value>) - stores the value <value> into the key <key>")
        println("\tread(<key>) - reads the value stored in the key <key>")
        println("\tm - prints replicas on the server")
        println("\thelp - prints the available commands")
        println("\tquit - quits the client")
    }

    def main(args: Array[String]): Unit = {
        val host = args(0)
        val port = args(1).toInt

        // Setup application.conf file for address and port of the ActorSystem
        val default_config = ConfigFactory.parseFile(new File("./src/resources/application.conf"))
        var config = default_config.withValue("akka.remote.netty.tcp.hostname", ConfigValueFactory.fromAnyRef(host))
        config = config.withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(port))

        val r0 = config.getString("membership.initial1").split(":")
        val r1 = config.getString("membership.initial2").split(":")
        val r2 = config.getString("membership.initial3").split(":")

        val rep0 = SocketAddress(r0(0), r0(1).toInt)
        val rep1 = SocketAddress(r1(0), r1(1).toInt)
        val rep2 = SocketAddress(r2(0), r2(1).toInt)
        val rep3 = SocketAddress("127.0.0.1", 2553)
        val rep4 = SocketAddress("127.0.0.1", 2554)
        val rep5 = SocketAddress("127.0.0.1", 2555)

        val membership = Set(rep0, rep1, rep2, rep3, rep4, rep5)

        // Setup client actor
        val system = ActorSystem("clientSystem", config)
        val client = system.actorOf(Props[Client], "client")
        val address = SocketAddress(host, port)

        client ! InitClient(address, membership)

        val writePattern = "(write.*)".r
        val readPattern = "(read.*)".r

        help()
        print(">")
        var cmd: String = ""
        while (cmd != "quit") {
            cmd = StdIn.readLine().toLowerCase
            cmd match {
                case writePattern(write) =>
                    val split1 = write.split("\\(")
                    val split2 = split1(1).split(", ")

                    val key = split2(0)
                    val value = split2(1).split("\\)")(0)

                    client ! WriteOp(key, value)

                case readPattern(read) =>
                    val split1 = read.split("\\(")
                    val split2 = split1(1).split("\\)")

                    val key = split2(0)

                    client ! ReadOp(key)

                case "m" =>
                    client ! "m"

                case "quit" =>
                    system.terminate()

                case "help" =>
                    help()

                case unknown: String =>
                    println("Unknown command: " + unknown)
            }
        }

    }
}

