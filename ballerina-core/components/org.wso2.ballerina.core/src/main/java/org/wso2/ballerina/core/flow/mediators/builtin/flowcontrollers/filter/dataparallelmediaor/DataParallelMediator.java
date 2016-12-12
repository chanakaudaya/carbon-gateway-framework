package org.wso2.ballerina.core.flow.mediators.builtin.flowcontrollers.filter.dataparallelmediaor;

/**
 * Created by wso2123 on 11/18/16.
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.ballerina.core.Constants;
import org.wso2.ballerina.core.config.Parameter;
import org.wso2.ballerina.core.config.ParameterHolder;
import org.wso2.ballerina.core.flow.AbstractFlowController;
import org.wso2.ballerina.core.flow.FlowControllerMediateCallback;
import org.wso2.ballerina.core.flow.Mediator;
import org.wso2.ballerina.core.flow.MediatorCollection;
import org.wso2.ballerina.core.util.VariableUtil;
import org.wso2.carbon.messaging.CarbonCallback;
import org.wso2.carbon.messaging.CarbonMessage;
import org.wso2.carbon.messaging.MessageUtil;
import rx.Observable;
import rx.schedulers.Schedulers;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs messages in parallel Threads
 */
public class DataParallelMediator extends AbstractFlowController {

    //child mediator collection
    private MediatorCollection childMediatorList = new MediatorCollection();

    //get available threads
    private final int thresholdConstant = 20;

    //parameter variable for xpath or json path
    private String localPath = "pathexpression";
    private String path = "";

    //message reference variable
    String messageRef = "";

    //available thread param
    private String localdesirdThread = "availablethreads";
    private int desiredThreads = 0;
    private boolean desiredthreadsSpecified = false;

    //threshold param
    private String localThreshold = "threshold";
    private int threshold = thresholdConstant;
    private boolean thresholdSpecified = false;

    //add single mediator
    public void addMediator(Mediator mediator) {
        childMediatorList.addMediator(mediator);
    }

    private static final Logger log = LoggerFactory.getLogger(DataParallelMediator.class);

    @Override
    public boolean receive(CarbonMessage carbonMessage, CarbonCallback carbonCallback) throws Exception {

        //Add message ID
        carbonMessage.setProperty("Message-ID", UUID.randomUUID().toString());

        //get message body
        List<ByteBuffer> messageBody = carbonMessage.getFullMessageBody();

        ArrayList<CarbonMessage> newCarbonMessages = new ArrayList<>();
        if (carbonMessage.getHeader("Content-Type").equals("application/xml")) {
            XmlSplitter ds = new XmlSplitter(messageRef, messageBody);
            try {
                newCarbonMessages = ds.splitXml(carbonMessage, path);
            } catch (Exception e) {
                log.error("Error in splitting xml");
                newCarbonMessages = new ArrayList<>();
            }
        } else {
            //TODO
            //handle json processing
        }

        //List<ByteBuffer> buf = carbonMessage.getFullMessageBody();
        /*CarbonMessage msg1 = MessageUtil.cloneCarbonMessageWithOutData(carbonMessage);
        for (ByteBuffer item : messageBody) {
            msg1.addMessageBody(item.duplicate());
        }
        msg1.setEndOfMsgAdded(true);
        msg1.setProperty(Constants.VARIABLE_STACK, null);
        VariableUtil.addVariable(msg1, messageRef, msg1);
        CarbonMessage msg2 = MessageUtil.cloneCarbonMessageWithOutData(carbonMessage);
        for (ByteBuffer item : messageBody) {
            msg2.addMessageBody(item.duplicate());
        }
        msg2.setEndOfMsgAdded(true);
        msg2.setProperty(Constants.VARIABLE_STACK, null);
        VariableUtil.addVariable(msg2, messageRef, msg2);
        CarbonMessage msg3 = MessageUtil.cloneCarbonMessageWithOutData(carbonMessage);
        for (ByteBuffer item : messageBody) {
            msg3.addMessageBody(item.duplicate());
        }
        msg3.setEndOfMsgAdded(true);
        msg3.setProperty(Constants.VARIABLE_STACK, null);
        VariableUtil.addVariable(msg3, messageRef, msg3);
        newCarbonMessages.add(msg1);
        newCarbonMessages.add(msg2);
        newCarbonMessages.add(msg3);*/
        //lenth of the carbon messages
        int len = newCarbonMessages.size();

        if (len > 0) {
            //specifying threshold
            if (thresholdSpecified) {
                threshold = (threshold < thresholdConstant) ? threshold : thresholdConstant;
            }

            //get the thread count
            final int threadCount;

            //determining thread value
            if (desiredthreadsSpecified) {
                threadCount = (desiredThreads < threshold) ?
                        ((desiredThreads < len) ? desiredThreads : len) :
                        ((threshold < len) ? threshold : len);
            } else {
                threadCount = threshold;
            }

            //create threads according to thread model
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
            final AtomicInteger group = new AtomicInteger(0);

            //executing messages in parallel
            Observable.from(newCarbonMessages).groupBy(i -> group.getAndIncrement() % threadCount)
                    .flatMap(threadMessage -> threadMessage.observeOn(Schedulers.from(executorService)).map(msg -> {
                        try {
                            msg.setProperty("Message-Count", Integer.toString(len));
                            if (!(childMediatorList.getMediators().isEmpty())) {
                                FlowControllerMediateCallback newcallBack = new FlowControllerMediateCallback(
                                        carbonCallback, this, VariableUtil.getVariableStack(msg));
                                super.receive(msg, newcallBack);
                                childMediatorList.getFirstMediator().
                                        receive(msg, newcallBack);
                            } else {
                                next(msg, carbonCallback);
                            }
                        } catch (Exception e) {
                            log.error("Error while mediating", e);
                        }
                        return true;
                    })).subscribe(ob -> {
                executorService.shutdown();
            });
        } else {
            //Message to handle faulty situations
            CarbonMessage forwardMessage = MessageUtil.cloneCarbonMessageWithOutData(carbonMessage);
            for (ByteBuffer item : messageBody) {
                forwardMessage.addMessageBody(item);
            }
            forwardMessage.setEndOfMsgAdded(true);
            forwardMessage.setProperty(Constants.VARIABLE_STACK, null);
            VariableUtil.addVariable(forwardMessage, messageRef, forwardMessage);

            try {
                forwardMessage.setProperty("Message-Count", 1);
                if (!(childMediatorList.getMediators().isEmpty())) {
                    FlowControllerMediateCallback newcallBack = new FlowControllerMediateCallback(carbonCallback, this,
                            VariableUtil.getVariableStack(forwardMessage));
                    super.receive(forwardMessage, newcallBack);
                    childMediatorList.getFirstMediator().
                            receive(forwardMessage, newcallBack);
                } else {
                    next(forwardMessage, carbonCallback);
                }
            } catch (Exception e) {
                log.error("Error while mediating", e);
            }
        }
        return true;
    }

    @Override
    public String getName() {
        return "dataparallel";
    }

    public void setParameters(ParameterHolder parameterHolder) {

        //set parameters
        Parameter pathParameter;
        Parameter desiredthreadParameter;
        Parameter thresholdParameter;
        Parameter messagerefParameter;

        if ((pathParameter = parameterHolder.getParameter(localPath)) != null) {
            path = pathParameter.getValue();
            parameterHolder.removeParameter(pathParameter.getName());
        } else {
            log.error("Path is not specified");
        }
        if ((desiredthreadParameter = parameterHolder.getParameter(localdesirdThread)) != null) {
            try {
                desiredThreads = Integer.parseInt(desiredthreadParameter.getValue());
                if (desiredThreads > 0) {
                    desiredthreadsSpecified = true;
                }
            } catch (NumberFormatException e) {
                desiredThreads = 0;
            }
            parameterHolder.removeParameter(desiredthreadParameter.getName());
        }
        if ((thresholdParameter = parameterHolder.getParameter(localThreshold)) != null) {
            try {
                threshold = Integer.parseInt(thresholdParameter.getValue());
                if (threshold > 0) {
                    thresholdSpecified = true;
                }
            } catch (NumberFormatException e) {
                threshold = thresholdConstant;
            }
            parameterHolder.removeParameter(thresholdParameter.getName());
        }
        if ((messagerefParameter = parameterHolder.getParameter("messageRef")) != null) {
            messageRef = messagerefParameter.getValue();
        }

    }

}
