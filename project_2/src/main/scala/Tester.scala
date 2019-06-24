import java.io.File

import akka.actor.{Actor, ActorSelection, ActorSystem, Props, Timers}
import com.typesafe.config.ConfigFactory

import scala.io.StdIn
import scala.util.Random

class Tester extends Actor with Timers {

    var keys: Set[String] = Set.empty
    var values: Set[String] = Set.empty
    var clients: Set[SocketAddress] = Set.empty
    var lastNumberOfOps: Int = 0
    var totalCpuTime: Long = 0
    var nDone: Int = 0
    var initialSize: Int = 3
    var currentSize: Int = 3
    var start: Long = 0
    var nRounds: Int = 5 // default
    var execRounds: Int = 0

    var totalLatency: Long = 0
    var totalThroughPut: Int = 0
    var totalElapsed: Long = 0


    override def receive: Receive = {
        case InitTester(c) =>
            clients = c
            for (i <- 0 until 70) {
                keys += "k" + i
                values += "v" + i
            }

            println("[OK] Tester initialized with 1 client @" + c.toString())

        case ChangeNRounds(n) =>
            nRounds = n
            println("[OK] Number of rounds set to " + nRounds + ".")
            print(">")

        case nw: Int =>
            println("[OK] " + nw + " writes per client.")
            lastNumberOfOps = nw

            var setCopy = clients
            while (setCopy.nonEmpty) {
                val client = setCopy.head
                var ops: Set[Op] = Set.empty
                for (_ <- 0 until nw)
                    ops += Op("write", Array(atRandom[String](keys), atRandom[String](values)), "")

                start = System.currentTimeMillis()
                clientASC(client) ! Requests(ops)
                setCopy -= client
            }

        case ClientDone(cputime, latency) =>
            totalCpuTime += cputime
            nDone += 1

            if (nDone == clients.size) {
                val elapsed = System.currentTimeMillis() - start
                totalElapsed += elapsed

                nDone = 0
                execRounds += 1
                collectResults(elapsed, latency / lastNumberOfOps)
                if (execRounds < nRounds)
                    self ! lastNumberOfOps
                else {
                    printResults()
                    reset()
                }

                //printResults(elapsed, lastNumberOfOps, latency)

            }

        case AddClient(c) =>
            if (!clients.contains(c)) {
                clients += c
                println("[OK] Clients online: " + clients + ".")
            } else
                println("[ERROR] Client " + c + " is already online.")

        case RemoveClient(c) =>
            if (clients.contains(c)) {
                clients -= c
                println("[OK] Clients online: " + clients + ".")
            } else
                println("[ERROR] Client " + c + " is not online.")

        case AddRep(r) =>
            clients.foreach(c => clientASC(c) ! AddRep(r))
            println("[OK].")

        case RemoveRep(r) =>
            clients.foreach(c => clientASC(c) ! RemoveRep(r))
            println("[OK].")

        case "c" =>
            println("[OK] Clients online: " + clients + ".")

        case x: Any =>
            println("Got unexpected msg <" + x + "> of type " + x.getClass)
    }

    def clientASC(address: SocketAddress): ActorSelection = {
        context.actorSelection("akka.tcp://clientSystem@" + address.toString + "/user/client")
    }

    def atRandom[T](set: Set[T]): T = {
        Random.shuffle(set.toList).drop(set.size - 1).head
    }

    def reset(): Unit = {
        execRounds = 0
        totalLatency = 0
        totalThroughPut = 0
        totalElapsed = 0
    }

    def collectResults(elapsed: Long, latency: Long): Unit = {
        totalLatency += latency
        totalThroughPut += ((lastNumberOfOps * 1000) / elapsed).toInt
    }

    def printResults(): Unit = {
        println("[DONE]\nExecution time per round (ms): " + totalElapsed / nRounds + "\n"
            + "Total operations per round: " + lastNumberOfOps * clients.size + " \n"
            + "Throughput (ops/s): " + totalThroughPut / nRounds + "\n"
            + "Latency (ms): " + totalLatency / nRounds)
        print(">")
    }
}

object Tester {
    def help(): Unit = {
        println("\tr <n: Int> - changes the number of rounds to <n>")
        println("\tw <n: Int> - commands each client to request <n> write operations")
        println("\tac <port: Int> - starts a client @127.0.0.1:<port> (port € [2541, 2549]")
        println("\trc <port: Int> - removes the client @127.0.0.1:<port> (port € [2541, 2549]")
        println("\tc - list clients online")
        println("\tar <port: Int> - adds a replica @127.0.0.1:<port> (port € [2550, 2559]")
        println("\trr <port: Int> - removes the replica @127.0.0.1:<port> (port € [2550, 2559]")
        println("\thelp - print commands")
        println("\tquit - quits (with system.terminate)")
    }

    def main(args: Array[String]): Unit = {
        // Setup tester actor
        val default_config = ConfigFactory.parseFile(new File("./src/resources/application.conf"))
        val system = ActorSystem("testerSystem", default_config)
        val tester = system.actorOf(Props[Tester], "tester")

        tester ! InitTester(Set(SocketAddress("127.0.0.1", 2540),
                                SocketAddress("127.0.0.1", 2541),
                                SocketAddress("127.0.0.1", 2542)))

        val r = "(r .*)".r
        val w = "(w .*)".r
        val ac = "(ac .*)".r
        val rc = "(rc .*)".r
        val ar = "(ar .*)".r
        val rr = "(rr .*)".r

        help()
        print(">")
        var cmd: String = ""
        while (cmd != "quit") {
            cmd = StdIn.readLine().toLowerCase
            cmd match {
                case r(input) =>
                    tester ! ChangeNRounds(input.split("\\s+")(1).toInt)

                case w(input) =>
                    tester ! input.split("\\s+")(1).toInt

                case ac(input) =>
                    tester ! AddClient(SocketAddress("127.0.0.1", input.split("\\s+")(1).toInt))

                case rc(input) =>
                    tester ! RemoveClient(SocketAddress("127.0.0.1", input.split("\\s+")(1).toInt))

                case "c" =>
                    tester ! "c"

                case ar(input) =>
                    tester ! AddRep(SocketAddress("127.0.0.1", input.split("\\s+")(1).toInt))

                case rr(input) =>
                    tester ! RemoveRep(SocketAddress("127.0.0.1", input.split("\\s+")(1).toInt))

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
