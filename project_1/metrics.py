# Change this value with number of nodes
NODES = input('Number of Nodes: ')

if NODES > 10:
    firstRange = 10
    if NODES > 20:
        secondRange = 10
        thirdRange = NODES - 20
    else:
        secondRange = NODES - 10
        thirdRange = 0
else:
    firstRange = NODES
    secondRange = 0
    thirdRange = 0

topicSubscriptions = {}
sumSentMessages = {}
publishSum = 0
sumReceivedMessages = {}
sent = 0
received = 0
plumTreeSum = 0
hyParViewSum = 0
eagerSum = 0
lazySum = 0
#####################################################################################
#                 Sum of messages sent by every node by topic
#####################################################################################
for x in range(firstRange):
    with open( "./src/main/outputs/publish_sent_by_port_255" + str(x) + ".txt") as f:
        for line in f:
            (key, val) = line.split()
            publishSum += int(val)
            if key in sumSentMessages:
                sumSentMessages[key] += int(val)
            else:
                sumSentMessages[key] = int(val)

for x in range(secondRange):
    with open( "./src/main/outputs/publish_sent_by_port_256" + str(x) + ".txt") as f:
        for line in f:
            (key, val) = line.split()
            publishSum += int(val)
            if key in sumSentMessages:
                sumSentMessages[key] += int(val)
            else:
                sumSentMessages[key] = int(val)

for x in range(thirdRange):
    with open( "./src/main/outputs/publish_sent_by_port_257" + str(x) + ".txt") as f:
        for line in f:
            (key, val) = line.split()
            publishSum += int(val)
            if key in sumSentMessages:
                sumSentMessages[key] += int(val)
            else:
                sumSentMessages[key] = int(val)

#####################################################################################
#                                      END
#####################################################################################

#####################################################################################
#                Sum of messages received by every node by topic
#####################################################################################
for x in range(firstRange):
    with open( "./src/main/outputs/publish_received_by_port_255" + str(x) + ".txt") as f:
        for line in f:
            (key, val) = line.split()
            if key in sumReceivedMessages:
                sumReceivedMessages[key] += int(val)
            else:
                sumReceivedMessages[key] = int(val)
            if key in topicSubscriptions:
                topicSubscriptions[key] += 1
            else:
                topicSubscriptions[key] = 1

for x in range(secondRange):
    with open( "./src/main/outputs/publish_received_by_port_256" + str(x) + ".txt") as f:
        for line in f:
            (key, val) = line.split()
            if key in sumReceivedMessages:
                sumReceivedMessages[key] += int(val)
            else:
                sumReceivedMessages[key] = int(val)
            if key in topicSubscriptions:
                topicSubscriptions[key] += 1
            else:
                topicSubscriptions[key] = 1

for x in range(thirdRange):
    with open( "./src/main/outputs/publish_received_by_port_257" + str(x) + ".txt") as f:
        for line in f:
            (key, val) = line.split()
            if key in sumReceivedMessages:
                sumReceivedMessages[key] += int(val)
            else:
                sumReceivedMessages[key] = int(val)
            if key in topicSubscriptions:
                topicSubscriptions[key] += 1
            else:
                topicSubscriptions[key] = 1

#####################################################################################
#                                      END
#####################################################################################
for key, value in sumReceivedMessages.iteritems():
    if key in sumSentMessages:
        sent += sumSentMessages[key] * topicSubscriptions[key]
    received += value

#####################################################################################
#                  Sum of messages sent by the plum tree
#####################################################################################
for x in range(firstRange):
    with open("./src/main/outputs/plumTree_received_255" + str(x) + ".txt") as f:
        for line in f:
            (eager, lazy) = line.split()
            eagerSum += int(eager)
            lazySum += int(lazy)

for x in range(secondRange):
    with open("./src/main/outputs/plumTree_received_256" + str(x) + ".txt") as f:
        for line in f:
            (eager, lazy) = line.split()
            eagerSum += int(eager)
            lazySum += int(lazy)

for x in range(thirdRange):
    with open("./src/main/outputs/plumTree_received_257" + str(x) + ".txt") as f:
        for line in f:
            (eager, lazy) = line.split()
            eagerSum += int(eager)
            lazySum += int(lazy)

#####################################################################################
#                                      END
#####################################################################################

#####################################################################################
#                  Sum of messages sent by the HyParView
#####################################################################################
for x in range(firstRange):
    with open("./src/main/outputs/hyParView_received_255" + str(x) + ".txt") as f:
        for line in f:
            try:
                hyParViewSum += int(line)
                break
            except ValueError:
                hyParViewSum += 0

for x in range(secondRange):
    with open("./src/main/outputs/hyParView_received_256" + str(x) + ".txt") as f:
        for line in f:
            try:
                hyParViewSum += int(line)
                break
            except ValueError:
                hyParViewSum += 0

for x in range(thirdRange):
    with open("./src/main/outputs/hyParView_received_257" + str(x) + ".txt") as f:
        for line in f:
            try:
                hyParViewSum += int(line)
                break
            except ValueError:
                hyParViewSum += 0

#####################################################################################
#                  Total of messages sent by the HyParView
#####################################################################################
hyParViewTotal = 0
for x in range(firstRange):
    with open("./src/main/outputs/hyParView_total_255" + str(x) + ".txt") as f:
        for line in f:
            hyParViewTotal += int(line)
            print("> Node " + str(x) + " disseminated " + line + " messages")

for x in range(secondRange):
    with open("./src/main/outputs/hyParView_total_256" + str(x) + ".txt") as f:
        for line in f:
            hyParViewTotal += int(line)
            print("> Node 1" + str(x) + " disseminated " + line + " messages")


#####################################################################################
#                                      END
#####################################################################################
print("> Processes received " + str(received) + " of " + str(sent) + " expected messages (" + str("{0:.2f}".format(float(received)/sent*100)) + "%)")

print("> PlumTree disseminated " + str(eagerSum) + " gossip messages and " + str(lazySum) + " lazy messages")

print("> HyParView disseminated " + str(hyParViewSum) + " messages for " +
      str(publishSum) + " publishes (" +
      str("{0:.2f}".format(abs(publishSum-hyParViewSum)/float(publishSum))) + " time(s) more messages)")

print("> HyParView total " + str(hyParViewTotal) + " messages for " +
      str(publishSum) + " publishes (" +
      str("{0:.2f}".format(abs(publishSum-hyParViewTotal)/float(publishSum))) + " time(s) more messages)")