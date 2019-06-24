import java.io.{BufferedWriter, File, FileWriter}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Timers}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import scala.concurrent.duration._

import scala.io.StdIn

class NodeActor extends Actor with ActorLogging with Timers {

    var deliveredMessages = Set.empty[(String, String)] // set of (Topic, Message) tuples //TODO: Probably delete this
    var subscriptions = Set.empty[String] // set of topics
    var hyparview: ActorRef = _ // hyparview reference
    var plumtree: ActorRef = _ // plumtree reference
    var self_node: Node = _ // properties of this node
    var receivedTracker = Map.empty[String, Integer] // Variables for test purposes

    override def receive: Receive = {

        case InitNode(node, hyparview_params, plumtree_params) =>
            this.self_node = node
            hyparview = context.actorOf(Props[HyParViewActor], "hyparview")
            hyparview ! InitHyParView(this.self_node, hyparview_params)
            plumtree = context.actorOf(Props[PlumTreeActor], "plumtree")
            plumtree ! InitPlumTree(this.self_node, plumtree_params)

            // Test App
            //timers.startSingleTimer("publish 40", "publish", 2.minutes)


        case Deliver(t, m) =>
            if( subscriptions.contains(t) ) {
                val tuple = (t, m)
                val value = receivedTracker.apply(t)
                deliveredMessages += tuple
                receivedTracker += (t -> (value + 1))
                println("Received Message [" + t + "] - " + m)
            }

        case NeighborDown(node) =>
            plumtree ! NeighborDown(node)

        case NeighborUp(node) =>
            plumtree ! NeighborUp(node)

        case Neighbors(nodes) =>
            plumtree ! Neighbors(nodes)
            println("[SUCCESS] Neighbors: " + nodes)

        case GetNeighbors =>
            hyparview ! GetNeighbors

        case Subscribe(t: String) =>
            if(!subscriptions.contains(t)){
                subscriptions += t
                receivedTracker += (t -> 0)
                println("[SUCCESS] Topic " + t + " subscribed.")
            } else
                println("[FAILURE] Topic " + t + " already subscribed.")

        case Unsubscribe(t: String) =>
            if(subscriptions.contains(t)){
                subscriptions -= t
                println("[SUCCESS] Topic " + t + " unsubscribed.")
            } else
                println("[FAILURE] Topic " + t + " was not subscribed.")

        case Publish(t: String, m: String) =>
            val tuple = (t, m)
            deliveredMessages += tuple
            plumtree ! Broadcast(t, m)
            println("[SUCCESS] Message " + m + " published.")

        case Quit =>
            println("[SUCCESS] Quiting...")
            context.system.terminate()

        case CreateReceivedFile =>
            var outputText = ""
            for( (k,v) <- receivedTracker){
                outputText += k + " " + v + "\n"
            }
            val path = "./src/main/outputs/publish_received_by_port_" + self_node.port + ".txt"
            val file = new File(path)
            val bw = new BufferedWriter(new FileWriter(file))
            bw.write(outputText)
            bw.close()
            println("[SUCCESS] Node created text file.")
            plumtree ! CreateReceivedFile
            hyparview ! CreateReceivedFile

        case "getHyparviewMsgCount" =>
            hyparview ! "getHyparviewMsgCount"

        //case "publish" =>


        case HyparviewMsgCount(count) =>
            val path = "./src/main/outputs/hyParView_total_" + self_node.port + ".txt"
            val file = new File(path)
            val bw = new BufferedWriter(new FileWriter(file))
            bw.write("" + count)
            bw.close()

        case _ =>
            log.info("any")

    }
}

object NodeActor {
    def main(args: Array[String]): Unit = {
        val address = args(0)
        val port = args(1).toInt

        // Setup application.conf file for address and port of the ActorSystem
        val default_config = ConfigFactory.parseFile(new File("./src/main/resources/application.conf"))
        var config = default_config.withValue("akka.remote.netty.tcp.hostname",
                                               ConfigValueFactory.fromAnyRef(address))
        config = config.withValue("akka.remote.netty.tcp.port",
                                   ConfigValueFactory.fromAnyRef(port))

        // Extract HyParView params
        val hyparview_params = HyparviewParams(
            Node(config.getString("hyparview.contact.hostname"),
                 config.getInt("hyparview.contact.port")),
            config.getInt("hyparview.num-nodes"),
            config.getInt("hyparview.active-view.RWL"),
            config.getInt("hyparview.passive-view.max-size"),
            config.getInt("hyparview.passive-view.RWL"),
            config.getInt("hyparview.passive-view.update-time"),
            config.getInt("hyparview.passive-view.shuffle.RWL"),
            config.getInt("hyparview.passive-view.shuffle.ka"),
            config.getInt("hyparview.passive-view.shuffle.kp"),
            config.getInt("hyparview.failure-probability"),
            config.getString("hyparview.output-path"))

        // Extract PlumTree params
        val plumtree_params = PlumTreeParams(config.getInt("plumtree.timeout1"),
                                             config.getInt("plumtree.timeout2"),
                                             config.getInt("plumtree.threshold"),
                                             config.getInt("plumtree.fanout"),
                                             config.getString("plumtree.outputPath"))

        // Setup main actor
        val system = ActorSystem("node_system", config)
        val node = system.actorOf(Props[NodeActor], "node")

        // Self and contact node
        val self_node = Node(address, port)

        node ! InitNode(self_node, hyparview_params, plumtree_params)

        // Show possible commands
        HelpCommands().help()

        // Subscribe to Topics
        val topics = TopicRandomizer().getRandomSet(5)
        for( t <- topics )
            node ! Subscribe(t)

        //if (port == 2550){
        Thread.sleep(300000)
        startPublishTest(node, port)
        Thread.sleep(300000)
        node ! CreateReceivedFile
        //}

        ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        //////////////////////////////////////////////// INPUTS ////////////////////////////////////////////////////////
        ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        var cmd: String = ""
        while (cmd != "quit") {
            cmd = StdIn.readLine().toLowerCase
            cmd match {
                case "neighbors" =>
                    node ! GetNeighbors

                case "subscribe" =>
                    print("Topic: ")
                    val topic = StdIn.readLine()
                    node ! Subscribe(topic)

                case "unsubscribe" =>
                    print("Topic: ")
                    val topic = StdIn.readLine()
                    node ! Unsubscribe(topic)

                case "publish" =>
                    print("Topic: ")
                    val topic = StdIn.readLine()
                    print("Message: ")
                    val message = StdIn.readLine()
                    node ! Publish(topic, message)

                case "quit" =>
                    node ! Quit

                case "help" =>
                    HelpCommands().help()

                case "start" =>
                    startPublishTest(node, port)
                    node ! "getHyparviewMsgCount"

                case "received" =>
                    node ! CreateReceivedFile

                case unknown: String =>
                    println("Unknown command: " + unknown)
            }
        }

    }


    def startPublishTest(node: ActorRef, port: Integer) {
        var sentracker = Map.empty[String, Integer]
        for (i <- 1 to 40) {
            val randomTopic = TopicRandomizer().getRandomTopic( System.currentTimeMillis() + port )

            try {
                val value = sentracker.apply(randomTopic)
                sentracker += (randomTopic -> (value + 1))
            }catch{
                case _ : Throwable =>
                    sentracker += (randomTopic -> 1)
            }

            node ! Publish( randomTopic, "message" + i)
            Thread.sleep(1000)
        }

        var outputText = ""
        for( (k,v) <- sentracker){
            outputText += k + " " + v + "\n"
        }

        val path = "./src/main/outputs/publish_sent_by_port_" + port + ".txt"
        val file = new File(path)
        val bw = new BufferedWriter(new FileWriter(file))
        bw.write(outputText)
        bw.close()
    }
}
