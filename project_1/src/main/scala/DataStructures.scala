// This file stores classes such as tuples
case class Node(address: String, port: Int) {
    override def toString(): String = address + ":" + port
}

case class HyparviewParams(contact_node: Node,
                           num_nodes: Int,
                           active_RWL: Int,
                           passive_view_max_size: Int,
                           passive_RWL: Int,
                           passive_update_time: Int,
                           shuffle_RWL: Int,
                           shuffle_ka: Int,
                           shuffle_kp: Int,
                           failure_probability: Int,
                           outputPath: String)

case class PlumTreeParams(timeout1: Integer,
                          timeout2: Integer,
                          threshold: Integer,
                          fanout: Integer,
                          outputPath: String)

case class TopicRandomizer(){
    def getRandomSet(size: Integer): Set[String] = {
        val randomizer = scala.util.Random.shuffle((0 to 49).toList)
        var topics = Set.empty[String]
        val (l, _) = randomizer.splitAt(size)
        for( t <- l){
            topics += ("T" + t)
        }
        topics
    }

    def getRandomTopic(seed: Long): String ={
        "T" + new scala.util.Random(seed).nextInt(50)
    }
}

case class HelpCommands(){
    def help(): Unit = {
        println("Command List")
        println("\tneighbors - prints a set with the current neighbors")
        println("\tsubscribe - subscribe to a message, being later prompted to input the topic")
        println("\tunsubscribe - unsubscribe to a message, being later prompted to input the topic")
        println("\tpublish - publish a message, being later prompted to input the topic and message")
        println("\tstart - starts a series of publish in random topics, with different messages")
        println("\treceived - creates a file with the information of received messages")
        println("\tquit - quits the system")
        println("\thelp - lists commands")
    }
}