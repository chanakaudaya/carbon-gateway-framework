#Implementation of Data Parallelism[not updated to the latest ballerina configurations]

Tested ballerina configuration
------------------------------
Currently the message splitting process is working only for xmls.DataParallel mediator is fully compatible with all the mediators.
You can send multiple messages to same backend at the same time and get the responses and join them according to a given xpath.

After building the gateway framework you can add following integration flow to the deployment and test the mediator.Parallel mediation of
messages and collect them according to the settings were completely done using <b>rx-Java</b> and some of the Java8 features.

<b>
@Path("/personData")<br>
@Source(interface="default")<br>
@Service(tags = {<br>
   "stock_info",<br>
   "stock_update"<br>
}, description = "Rest api for get stocks details", produces = MediaType.APPLICATION_XML)<br>
package com.sample;<br>
constant endpoint stockEP = new EndPoint("-----Your endpoint name here------");<br>

@GET<br>
@PUT<br>
@POST<br>
@Path("/getData")<br>
resource filter(message m) {<br>
	&nbsp;&nbsp;dataParallel(messageRef=m,pathexpression="---xpath for splitting xmls-----",availablethreads="",threshold="") {<br>
    		&nbsp;&nbsp;message response;<br>
   	 	&nbsp;&nbsp;log(level="custom", status="Message Received...");<br>
    		&nbsp;&nbsp;&nbsp;&nbsp;if (eval(messageRef=m, path="$header.exchange") == "NYSE") {<br>
        		&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;if (eval(messageRef=m, path="$header.sub.exchange") == "ONE") {<br>
            			&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;log(level="custom", status="Exchange NYSE, sub-exchange ONE");<br>
        		&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;} else {<br>
            			&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;log(level="custom", status="Exchange NYSE, sub-exchange not ONE");<br>
       	 		}	<br>
    		&nbsp;&nbsp;&nbsp;&nbsp;} else {<br>
        		&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;if (eval(messageRef=m, path="$header.sub.exchange") == "ONE") {<br>
            			&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;log(level="custom", status="Exchange not NYSE, sub-exchange ONE");<br>
        		&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;} else {<br>
            			&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;log(level="custom", status="Exchange not NYSE, sub-exchange not ONE");<br>
        		&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;}<br>
    		&nbsp;&nbsp;&nbsp;&nbsp;}<br>
    		&nbsp;&nbsp;&nbsp;&nbsp;log(level="custom", status="Message sent to Endpoint...");<br>
    		&nbsp;&nbsp;&nbsp;&nbsp;response = invoke(endpointRef=stockEP, messageRef=m);<br>
		&nbsp;&nbsp;&nbsp;&nbsp;joinmediator(messageRef=response,joinPath="---path for joining messages-------",messagecount="",timeout="");<br>
    		&nbsp;&nbsp;&nbsp;&nbsp;log(level="custom", status="Response sent back to client...");<br>
    		&nbsp;&nbsp;&nbsp;&nbsp;reply response;<br>
	}<br>
}<br></b>

Setup a proxy service(backend) which send back an xml for a received request.

Send following request with a message body

http://localhost:9090/personData/getData

For this sample<br><b>
pathexpression="//persons/person"<br>
joinpath="//person"</b><br>

You can define the thread model using <b>availablethreads="" and threshold=""</b><br>
You can define the messages count needed to be aggregated and a timout using,<br> <b>messagecount="",timeout=""(Same as aggregate mediator in ESB)</b><br>

DataParallelMediator,XML splitter and JoinMediator can be found in<br>
<b>ballerinacore/components/org.wso2.ballerina.core/src/main/java/org/wso2/ballerina/core/flow/mediators/builtin/flowcontrollers/filter/dataparallelmediaor</b>


<br>Sample message body
```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
	<persons>
    		<person>
        	<id>person0</id>
		<name>name0</name>
		<age>age0</age>
    	</person>
    	<person>
    	       <id>person1</id>
               <name>name1</name>
	       <age>age01</age>
    	</person>
	</persons>```
	
(Body can extend further)
