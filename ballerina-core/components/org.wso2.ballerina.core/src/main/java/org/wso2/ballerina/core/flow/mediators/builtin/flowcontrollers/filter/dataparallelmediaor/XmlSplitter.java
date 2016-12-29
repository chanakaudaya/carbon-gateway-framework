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
import org.wso2.ballerina.core.flow.contentaware.ByteBufferBackedInputStream;
import org.wso2.ballerina.core.util.VariableUtil;
import org.wso2.carbon.messaging.CarbonMessage;
import org.wso2.carbon.messaging.MessageUtil;

import java.io.InputStream;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

/**
 * splitter to split xml.
 */
public class XmlSplitter {

    String messageRef;
    List<ByteBuffer> messageBody;

    XmlSplitter(String messageRef, List<ByteBuffer> messageBody) {
        this.messageRef = messageRef;
        this.messageBody = messageBody;
    }

    private static final Logger log = LoggerFactory.getLogger(XmlSplitter.class);

    public synchronized ArrayList<CarbonMessage> splitXml(CarbonMessage cMsg, String path) throws Exception {

        //Retrieve body from carbon message
        LinkedBlockingQueue<ByteBuffer> msgBody = new LinkedBlockingQueue(messageBody);
        InputStream inputStream = new ByteBufferBackedInputStream(msgBody);

        //xpath
        ArrayList<CarbonMessage> newCarbonMessages = new ArrayList();
        String[] xpathArray = path.substring(2, path.length()).split("/");

        //Xml processing
        Properties systemProperties = System.getProperties();
        systemProperties.remove("javax.xml.parsers.DocumentBuilderFactory");
        System.setProperties(systemProperties);
        DocumentBuilderFactory domFactory = DocumentBuilderFactory.newInstance();
        domFactory.setIgnoringComments(true);
        domFactory.setValidating(true);
        domFactory.setNamespaceAware(true);
        DocumentBuilder builder = domFactory.newDocumentBuilder();
        builder.setErrorHandler(new XmlErrorHandler());
        Document doc = builder.parse(inputStream);
        //builder.reset();
        XPath xPath = XPathFactory.newInstance().newXPath();
        XPathExpression exp = xPath.compile("//" + xpathArray[xpathArray.length - 1]);
        NodeList nl = (NodeList) exp.evaluate(doc, XPathConstants.NODESET);

        //setting up root elements
        String rootelementInit = "", rootelementEnd = "";
        int len = xpathArray.length;
        for (int m = 0; m < len - 1; m++) {
            rootelementInit = rootelementInit.concat("<" + xpathArray[m] + ">");
            rootelementEnd = rootelementEnd.concat("</" + xpathArray[len - 2 - m] + ">");
        }

        for (int i = 0; i < nl.getLength(); i++) {
            Node node = nl.item(i);
            StringWriter buf = new StringWriter();
            Transformer transformer = TransformerFactory.newInstance().newTransformer();

            //------------------OUTPUT_PROPERTIES-----------------------//
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            //------------------OUTPUT_PROPERTIES-----------------------//

            transformer.transform(new DOMSource(node), new StreamResult(buf));
            String rootlessBuf = buf.toString();

            String declaration = "";
            char[] arrayofbuf = rootlessBuf.toCharArray();

            for (int count = 1; count < arrayofbuf.length; count++) {
                if (arrayofbuf[count] == '>') {
                    declaration = rootlessBuf.substring(0, count + 1);
                    rootlessBuf = rootlessBuf.substring(count + 1, rootlessBuf.length());
                    break;
                }
            }

            String newXml = declaration + rootelementInit + rootlessBuf + rootelementEnd;
            newCarbonMessages.add(buildCarbonMessage(cMsg, declaration, newXml));
        }
        log.info("Message splitting completed");
        return newCarbonMessages;
    }

    private CarbonMessage buildCarbonMessage(CarbonMessage carbonMessage, String declaration, String newXml)
            throws Exception {

        //prepare byte array
        byte[] b;
        if (declaration.contains("UTF-16")) {
            b = newXml.getBytes(StandardCharsets.UTF_16);
        } else if (declaration.contains("ISO_8859_1")) {
            b = newXml.getBytes(StandardCharsets.ISO_8859_1);
        } else {
            b = newXml.getBytes(StandardCharsets.UTF_8);
        }

        //setting up new message
        CarbonMessage newMessage = MessageUtil.cloneCarbonMessageWithOutData(carbonMessage);
        newMessage.setBufferContent(true);
        if (b.length > 1024) {
            int count = 1024;
            int init = 0;
            while (count < b.length) {
                ByteBuffer newbody = ByteBuffer.allocateDirect(1024);
                newbody.put(Arrays.copyOfRange(b, init, count));
                init = count;
                count = (((count + 1024) < b.length) ? count + 1024 : b.length);
                newbody.clear();
                newMessage.addMessageBody(newbody);
            }
        } else {
            ByteBuffer newbody = ByteBuffer.allocateDirect(1024);
            newbody.put(b);
            newbody.clear();
            newMessage.addMessageBody(newbody);
        }
        newMessage.setEndOfMsgAdded(true);
        newMessage.setProperty(Constants.VARIABLE_STACK, null);
        VariableUtil.addVariable(newMessage, messageRef, newMessage);

        return newMessage;
    }

}
