/*
 * (C) Copyright 2015 Netcentric AG.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.cq.tools.actool.history.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFormatException;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.version.VersionException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.commons.JcrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import biz.netcentric.cq.tools.actool.comparators.TimestampPropertyComparator;
import biz.netcentric.cq.tools.actool.history.PersistableInstallationLogger;
import biz.netcentric.cq.tools.actool.installhook.AcToolInstallHook;
import biz.netcentric.cq.tools.actool.jmx.AceServiceMBeanImpl;
import biz.netcentric.cq.tools.actool.webconsole.AcToolWebconsolePlugin;

public class HistoryUtils {

    private static final Logger LOG = LoggerFactory.getLogger(HistoryUtils.class);

    public static final String LOG_FILE_NAME = "actool.log";
    public static final String LOG_FILE_NAME_VERBOSE = "actool-verbose.log";

    public static final String HISTORY_NODE_NAME_PREFIX = "history_";
    public static final String NODETYPE_NT_UNSTRUCTURED = "nt:unstructured";
    private static final String PROPERTY_SLING_RESOURCE_TYPE = "sling:resourceType";
    public static final String ACHISTORY_ROOT_NODE = "achistory";
    public static final String STATISTICS_ROOT_NODE = "var/statistics";

    public static final String PROPERTY_TIMESTAMP = "timestamp";
    private static final String PROPERTY_MESSAGES = "messages";
    private static final String PROPERTY_EXECUTION_TIME = "executionTime";
    public static final String PROPERTY_SUCCESS = "success";
    private static final String PROPERTY_INSTALLATION_DATE = "installationDate";
    public static final String PROPERTY_INSTALLED_FROM = "installedFrom";


    public static Node getAcHistoryRootNode(final Session session)
            throws RepositoryException {
        final Node rootNode = session.getRootNode();
        Node statisticsRootNode = safeGetNode(rootNode, STATISTICS_ROOT_NODE,
                NODETYPE_NT_UNSTRUCTURED);
        Node acHistoryRootNode = safeGetNode(statisticsRootNode,
                ACHISTORY_ROOT_NODE, "sling:OrderedFolder");
        return acHistoryRootNode;
    }

    /**
     * Method that persists a new history log in CRX under
     * '/var/statistics/achistory'
     * 
     * @param session the jcr session
     * @param installLog
     *            history to persist
     * @param nrOfHistoriesToSave
     *            number of newest histories which should be kept in CRX. older
     *            histories get automatically deleted
     * @return the node being created
     */
    public static Node persistHistory(final Session session,
            PersistableInstallationLogger installLog, final int nrOfHistoriesToSave)
            throws RepositoryException {

        Node acHistoryRootNode = getAcHistoryRootNode(session);
        String name = HISTORY_NODE_NAME_PREFIX + System.currentTimeMillis();
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        
        if (StringUtils.isNotBlank(installLog.getCrxPackageName())) {
            name += "_via_hook_in_" + installLog.getCrxPackageName();
        } else if(isInStrackTracke(stackTrace, AceServiceMBeanImpl.class)) {
            name += "_via_jmx";
        } else if(isInStrackTracke(stackTrace, AcToolWebconsolePlugin.class)) {
            name += "_via_webconsole";
        } else {
            name += "_via_api";
        }
        
        Node newHistoryNode = safeGetNode(acHistoryRootNode, name, NODETYPE_NT_UNSTRUCTURED);
        String path = newHistoryNode.getPath();
        setHistoryNodeProperties(newHistoryNode, installLog);
        
        // not ideal to save both variants, but the easiest for now
        JcrUtils.putFile(newHistoryNode, LOG_FILE_NAME_VERBOSE, "text/plain",
                new ByteArrayInputStream(installLog.getVerboseMessageHistory().getBytes()));
        JcrUtils.putFile(newHistoryNode, LOG_FILE_NAME, "text/plain",
                new ByteArrayInputStream(installLog.getMessageHistory().getBytes()));
        
        deleteObsoleteHistoryNodes(acHistoryRootNode, nrOfHistoriesToSave);

        Node previousHistoryNode = (Node) acHistoryRootNode.getNodes().next();
        if (previousHistoryNode != null) {
            acHistoryRootNode.orderBefore(newHistoryNode.getName(),
                    previousHistoryNode.getName());
        }

        installLog.addMessage(LOG, "Saved history in node: " + path);
        return newHistoryNode;
    }

    private static boolean isInStrackTracke(StackTraceElement[] stackTrace, Class<?> classToSearch) {
        for (StackTraceElement stackTraceElement : stackTrace) {
            if(classToSearch.getName().equals(stackTraceElement.getClassName())) { 
                return true;
            }
        }
        return false;
    }

    private static Node safeGetNode(final Node baseNode, final String name,
            final String typeToCreate) throws RepositoryException {
        if (!baseNode.hasNode(name)) {
            LOG.debug("create node: {}", name);
            return baseNode.addNode(name, typeToCreate);

        } else {
            return baseNode.getNode(name);
        }
    }

    public static void setHistoryNodeProperties(final Node historyNode,
            PersistableInstallationLogger installLog) throws ValueFormatException,
            VersionException, LockException, ConstraintViolationException,
            RepositoryException {

        historyNode.setProperty(PROPERTY_INSTALLATION_DATE, installLog
                .getInstallationDate().toString());
        historyNode.setProperty(PROPERTY_SUCCESS, installLog.isSuccess());
        historyNode.setProperty(PROPERTY_EXECUTION_TIME,
                installLog.getExecutionTime());

        historyNode.setProperty(PROPERTY_TIMESTAMP, installLog
                .getInstallationDate().getTime());
        historyNode.setProperty(PROPERTY_SLING_RESOURCE_TYPE,
                "/apps/netcentric/actool/components/historyRenderer");

        Map<String, String> configFileContentsByName = installLog.getConfigFileContentsByName();
        if (configFileContentsByName != null) {
            String commonPrefix = StringUtils
                    .getCommonPrefix(configFileContentsByName.keySet().toArray(new String[configFileContentsByName.size()]));
            String crxPackageName = installLog.getCrxPackageName(); // for install hook case
            historyNode.setProperty(PROPERTY_INSTALLED_FROM, StringUtils.defaultString(crxPackageName) + commonPrefix);
        }

    }

    /**
     * Method that ensures that only the number of history logs is persisted in
     * CRX which is configured in nrOfHistoriesToSave
     * 
     * @param acHistoryRootNode
     *            node in CRX under which the history logs are located
     * @param nrOfHistoriesToSave
     *            number of history logs which get stored in CRX (as direct
     *            child nodes of acHistoryRootNode in insertion order - newest
     *            always on top)
     * @throws RepositoryException
     */
    private static void deleteObsoleteHistoryNodes(
            final Node acHistoryRootNode, final int nrOfHistoriesToSave)
            throws RepositoryException {
        NodeIterator childNodeIt = acHistoryRootNode.getNodes();
        Set<Node> historyChildNodes = new TreeSet<Node>(
                new TimestampPropertyComparator());

        while (childNodeIt.hasNext()) {
            Node node = childNodeIt.nextNode();
            if (node.getName().startsWith(HISTORY_NODE_NAME_PREFIX)) {
                historyChildNodes.add(node);
            }
        }
        int index = 1;
        for (Node node : historyChildNodes) {
            if (index > nrOfHistoriesToSave) {
                LOG.debug("delete obsolete history node: ", node.getPath());
                node.remove();
            }
            index++;
        }
    }

    /**
     * Method that returns a string which contains a number, path of a stored
     * history log in CRX, and the success status of that installation
     * 
     * @param acHistoryRootNode
     *            node in CRX under which the history logs get stored
     * @return String array which holds the single history infos
     * @throws RepositoryException
     * @throws PathNotFoundException
     */
    static String[] getHistoryInfos(final Session session)
            throws RepositoryException, PathNotFoundException {
        Node acHistoryRootNode = getAcHistoryRootNode(session);
        Set<String> messages = new LinkedHashSet<String>();
        int cnt = 1;
        for (NodeIterator iterator = acHistoryRootNode.getNodes(); iterator
                .hasNext();) {
            Node node = (Node) iterator.next();
            if (node != null && node.getName().startsWith("history_")) {
                String successStatusString = "failed";
                if (node.getProperty(PROPERTY_SUCCESS).getBoolean()) {
                    successStatusString = "ok";
                }
                String installationDate = node.getProperty(
                        PROPERTY_INSTALLATION_DATE).getString();
                messages.add(cnt + ". " + node.getPath() + " " + "" + "("
                        + installationDate + ")" + "(" + successStatusString
                        + ")");
            }
            cnt++;
        }
        return messages.toArray(new String[messages.size()]);
    }

    public static String getLogTxt(final Session session, final String path, boolean includeVerbose) {
        return getLog(session, path, "\n", includeVerbose).toString();
    }

    public static String getLogHtml(final Session session, final String path, boolean includeVerbose) {
        return getLog(session, path, "<br />", includeVerbose).toString();
    }

    /**
     * Method which assembles String containing informations of the properties
     * of the respective history node which is specified by the path parameter
     */
    public static String getLog(final Session session, final String path,
            final String lineFeedSymbol, boolean includeVerbose) {

        StringBuilder sb = new StringBuilder();
        try {
            Node acHistoryRootNode = getAcHistoryRootNode(session);
            Node historyNode = acHistoryRootNode.getNode(path);

            if (historyNode != null) {
                sb.append("Installation triggered: "
                        + historyNode.getProperty(PROPERTY_INSTALLATION_DATE)
                                .getString());
                
                if(historyNode.hasProperty(PROPERTY_MESSAGES)) {
                    sb.append(lineFeedSymbol
                            + historyNode.getProperty(PROPERTY_MESSAGES)
                                    .getString().replace("\n", lineFeedSymbol));
                } else {
                    Node logFileNode;
                    if(includeVerbose) {
                        logFileNode = historyNode.getNode(LOG_FILE_NAME_VERBOSE);
                    } else {
                        logFileNode = historyNode.getNode(LOG_FILE_NAME);
                    }
                    sb.append(lineFeedSymbol
                            +  IOUtils.toString(JcrUtils.readFile(logFileNode)).replace("\n", lineFeedSymbol));
                }

                sb.append(lineFeedSymbol
                        + "Execution time: "
                        + historyNode.getProperty(PROPERTY_EXECUTION_TIME)
                                .getLong() + " ms");
                sb.append(lineFeedSymbol
                        + "Success: "
                        + historyNode.getProperty(PROPERTY_SUCCESS)
                                .getBoolean());
            }
        } catch (IOException|RepositoryException e) {
            sb.append(lineFeedSymbol+"ERROR while retrieving log: "+e);
            LOG.error("ERROR while retrieving log: "+e, e);
        }
        return sb.toString();
    }

}
