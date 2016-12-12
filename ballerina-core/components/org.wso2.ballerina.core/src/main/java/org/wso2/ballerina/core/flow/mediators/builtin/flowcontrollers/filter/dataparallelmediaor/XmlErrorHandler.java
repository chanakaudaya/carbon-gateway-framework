package org.wso2.ballerina.core.flow.mediators.builtin.flowcontrollers.filter.dataparallelmediaor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * handle errors in xml
 */
public class XmlErrorHandler implements ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(XmlErrorHandler.class);

    public void warning(SAXParseException e) throws SAXException {
        log.error(e.getMessage());
    }

    public void error(SAXParseException e) throws SAXException {
        log.error(e.getMessage());
    }

    public void fatalError(SAXParseException e) throws SAXException {
        log.error(e.getMessage());
    }
}
