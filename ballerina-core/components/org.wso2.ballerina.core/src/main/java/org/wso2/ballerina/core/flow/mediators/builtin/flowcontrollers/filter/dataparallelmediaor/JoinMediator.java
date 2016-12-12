package org.wso2.ballerina.core.flow.mediators.builtin.flowcontrollers.filter.dataparallelmediaor;

/**
 * Created by wso2123 on 11/18/16.
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.wso2.ballerina.core.Constants;
import org.wso2.ballerina.core.config.Parameter;
import org.wso2.ballerina.core.config.ParameterHolder;
import org.wso2.ballerina.core.flow.AbstractMediator;
import org.wso2.ballerina.core.flow.contentaware.ByteBufferBackedInputStream;
import org.wso2.ballerina.core.util.VariableUtil;
import org.wso2.carbon.messaging.CarbonCallback;
import org.wso2.carbon.messaging.CarbonMessage;
import org.wso2.carbon.messaging.DefaultCarbonMessage;
import org.wso2.carbon.messaging.MessageUtil;
import rx.Observable;
import rx.subjects.PublishSubject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Join parallel processed messages
 */
public class JoinMediator extends AbstractMediator {

    //constant
    private final int defaultTimeout = 1000;

    //message reference variable
    String messageRef = "";

    //message count
    private String localmessageCount = "messagecount";
    private int messageCount = 0;
    private boolean messagecountSpecified = false;

    //timeout
    private String localtimeout = "timeout";
    private int timeout = 0;
    private boolean timeoutSpecified = false;

    //aggregate path
    private String localjoinPath = "joinPath";
    private String joinPath = "";
    private boolean joinpathtSpecified = false;

    //message type
    String messagetype = "";
    String messageID = "";
    int messages = 0;

    //hash map for store multiple messages
    Map<String, ObservableList> joinmessageList = new HashMap<>();

    private static final Logger log = LoggerFactory.getLogger(JoinMediator.class);

    @Override
    public String getName() {
        return "joinmediator";
    }

    @Override
    public boolean receive(CarbonMessage carbonMessage, CarbonCallback carbonCallback) throws Exception {

        synchronized (joinmessageList) {

            //access message id and message count
            Stack<Map<String, Object>> variableStack = ((Stack<Map<String, Object>>) carbonMessage
                    .getProperty(Constants.VARIABLE_STACK));
            HashMap<String, Object> getMap = (HashMap) variableStack.peek();

            boolean stackFound = false;
            //Traversing stack

            for (Map.Entry<String, Object> outer : getMap.entrySet()) {
                HashMap<String, Object> innerMap = (HashMap) outer.getValue();
                for (Map.Entry<String, Object> inner : innerMap.entrySet()) {
                    Object obj = inner.getValue();
                    if (obj instanceof DefaultCarbonMessage) {
                        CarbonMessage stackMsg = (CarbonMessage) obj;
                        messageID = stackMsg.getProperty("Message-ID").toString();
                        messages = Integer.parseInt(stackMsg.getProperty("Message-Count").toString());
                        messagetype = stackMsg.getHeader("Content-Type");
                        stackFound = true;
                        break;
                    }
                }
                if (stackFound) {
                    break;
                }
            }

            //collect messages according to messageID
            if (!joinmessageList.containsKey(messageID)) {
                messageCount = (messagecountSpecified) ?
                        ((messages < messageCount) ? messages : messageCount) :
                        messages;
                timeout = (timeoutSpecified) ? timeout : defaultTimeout;
                joinmessageList.put(messageID, new ObservableList(timeout, messageCount));
                joinmessageList.get(messageID).getObservable().subscribe(messageList -> {
                    log.info("Buffer completed");
                    List<CarbonMessage> collectedMessages = (List<CarbonMessage>) messageList;
                    if (joinpathtSpecified) {
                        try {
                            CarbonMessage newMessage = mergeMessages(joinPath, collectedMessages, messagetype);
                            next(newMessage, carbonCallback);
                        } catch (Exception e) {
                            log.error("Error in Join Mediator Mediation");
                        }
                    }
                });

            }

            //use the existing object if message with same ID arrived
            if (joinmessageList.containsKey(messageID)) {
                joinmessageList.get(messageID).add(carbonMessage);
            }

        }
        return true;
    }

    public void setParameters(ParameterHolder parameterHolder) {

        //path parameters
        Parameter pathParameter;
        Parameter messagecountParameter;
        Parameter timeoutParameter;
        Parameter messagerefParameter;

        if ((pathParameter = parameterHolder.getParameter(localjoinPath)) != null) {
            joinPath = pathParameter.getValue();
            joinpathtSpecified = true;
            parameterHolder.removeParameter(pathParameter.getName());
        } else {
            joinPath = "";
            log.info("Path is not specified");
        }
        if ((messagecountParameter = parameterHolder.getParameter(localmessageCount)) != null) {
            try {
                messageCount = Integer.parseInt(messagecountParameter.getValue());
                if (messageCount > 0) {
                    messagecountSpecified = true;
                }
            } catch (NumberFormatException e) {

            }
            parameterHolder.removeParameter(messagecountParameter.getName());
        }
        if ((timeoutParameter = parameterHolder.getParameter(localtimeout)) != null) {
            try {
                timeout = Integer.parseInt(timeoutParameter.getValue());
                if (timeout > 0) {
                    timeoutSpecified = true;
                }
            } catch (NumberFormatException e) {

            }
            parameterHolder.removeParameter(timeoutParameter.getName());
        }

        if ((messagerefParameter = parameterHolder.getParameter("messageRef")) != null) {
            messageRef = messagerefParameter.getValue();
        }

    }

    /**
     * Buffer initialize clss
     */
    public static class ObservableList {

        protected final List<CarbonMessage> list;
        protected final PublishSubject<CarbonMessage> onAdd;
        int timeout;
        int messagecount;

        public ObservableList(int timeout, int messagecount) {
            this.timeout = timeout;
            this.messagecount = messagecount;
            this.list = new ArrayList<>();
            this.onAdd = PublishSubject.create();
        }

        public void add(CarbonMessage value) {
            list.add(value);
            onAdd.onNext(value);
        }

        public Observable<List<CarbonMessage>> getObservable() {
            return onAdd.buffer(timeout, TimeUnit.SECONDS, messagecount);
        }

    }

    //merge if xpath is given
    private CarbonMessage mergeMessages(String mergePath, List<CarbonMessage> collectedMessages, String messageType)
            throws Exception {

        mergePath = mergePath.replace("//", "");

        if (collectedMessages.size() == 1) {
            return collectedMessages.get(0);
        } else {
            CarbonMessage newMessage = MessageUtil.cloneCarbonMessageWithOutData(collectedMessages.get(0));

            if (messageType.contains("xml")) {

                ArrayList<InputStream> messages = new ArrayList<>();
                collectedMessages.forEach(msg -> {
                    BlockingQueue<ByteBuffer> contentBuf = new LinkedBlockingQueue(msg.getFullMessageBody());
                    InputStream inputStream = new ByteBufferBackedInputStream(contentBuf);
                    messages.add(inputStream);
                });

                byte[] b = new byte[1024];

                DocumentBuilderFactory domFactory = DocumentBuilderFactory.newInstance();
                domFactory.setIgnoringComments(true);
                DocumentBuilder builder = domFactory.newDocumentBuilder();

                //initializing variables for splitting process
                Document fMsg, sMsg;
                NodeList fNodes, sNodes;
                while (messages.size() > 1) {

                    fMsg = builder.parse(messages.get(0));
                    sMsg = builder.parse(messages.get(1));

                    fNodes = fMsg.getElementsByTagName(mergePath);
                    sNodes = sMsg.getElementsByTagName(mergePath);

                    for (int i = 0; i < sNodes.getLength(); i = i + 1) {
                        Node n = (Node) fMsg.importNode(sNodes.item(i), true);
                        fNodes.item(i).getParentNode().appendChild(n);
                    }

                    StringWriter buf = new StringWriter();
                    Transformer transformer = TransformerFactory.newInstance().newTransformer();
                    //------------------OUTPUT_PROPERTIES-----------------------//
                    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
                    //------------------OUTPUT_PROPERTIES-----------------------//

                    transformer.transform(new DOMSource(fMsg), new StreamResult(buf));

                    String rootlessBuf = buf.toString();

                    if (rootlessBuf.contains("UTF-16")) {
                        b = rootlessBuf.getBytes(StandardCharsets.UTF_16);
                    } else if (rootlessBuf.contains("ISO_8859_1")) {
                        b = rootlessBuf.getBytes(StandardCharsets.ISO_8859_1);
                    } else {
                        b = rootlessBuf.getBytes(StandardCharsets.UTF_8);
                    }

                    InputStream xmlOutput = new ByteArrayInputStream(b);

                    //removing aggregated messages
                    messages.remove(0);
                    messages.remove(0);

                    //adding new message to the list
                    messages.add(0, xmlOutput);
                }

                ByteBuffer newBuffer = ByteBuffer.allocate(b.length * 2);
                newBuffer.put(b);
                Buffer n = newBuffer.clear();
                newMessage.addMessageBody((ByteBuffer) n);
                newMessage.setEndOfMsgAdded(true);
                newMessage.setProperty(Constants.VARIABLE_STACK, null);
                VariableUtil.addVariable(newMessage, messageRef, newMessage);
            } else {
                for (CarbonMessage cmsg : collectedMessages) {
                    List<ByteBuffer> buf = cmsg.getFullMessageBody();
                    for (ByteBuffer item : buf) {
                        newMessage.addMessageBody(item);
                    }
                }
            }
            return newMessage;
        }
    }
}
