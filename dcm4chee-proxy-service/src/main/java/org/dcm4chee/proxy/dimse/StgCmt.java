/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2012
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4chee.proxy.dimse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import org.dcm4che.conf.api.ApplicationEntityCache;
import org.dcm4che.conf.api.ConfigurationException;
import org.dcm4che.data.Attributes;
import org.dcm4che.data.Sequence;
import org.dcm4che.data.Tag;
import org.dcm4che.data.UID;
import org.dcm4che.data.VR;
import org.dcm4che.io.DicomOutputStream;
import org.dcm4che.net.ApplicationEntity;
import org.dcm4che.net.Association;
import org.dcm4che.net.Commands;
import org.dcm4che.net.Dimse;
import org.dcm4che.net.DimseRSPHandler;
import org.dcm4che.net.IncompatibleConnectionException;
import org.dcm4che.net.Status;
import org.dcm4che.net.pdu.AAssociateRJ;
import org.dcm4che.net.pdu.AAssociateRQ;
import org.dcm4che.net.pdu.PresentationContext;
import org.dcm4che.net.pdu.RoleSelection;
import org.dcm4che.net.service.DicomService;
import org.dcm4che.net.service.DicomServiceException;
import org.dcm4chee.proxy.common.RetryObject;
import org.dcm4chee.proxy.conf.ForwardOption;
import org.dcm4chee.proxy.conf.ForwardRule;
import org.dcm4chee.proxy.conf.ProxyAEExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Michael Backhaus <michael.backhaus@agfa.com>
 */
public class StgCmt extends DicomService {

    protected static final Logger LOG = LoggerFactory.getLogger(StgCmt.class);

    private ApplicationEntityCache aeCache;

    public StgCmt(ApplicationEntityCache aeCache) {
        super(UID.StorageCommitmentPushModelSOPClass);
        this.aeCache = aeCache;
    }

    @Override
    public void onDimseRQ(Association asAccepted, PresentationContext pc, Dimse dimse, Attributes rq, Attributes data)
            throws IOException {
        switch (dimse) {
        case N_EVENT_REPORT_RQ:
            processNEventReportRQ(asAccepted, pc, dimse, rq, data);
            break;
        case N_ACTION_RQ:
            processNActionRQ(asAccepted, pc, dimse, rq, data);
            break;
        default:
            super.onDimseRQ(asAccepted, pc, dimse, rq, data);
        }
    }

    private void processNActionRQ(Association asAccepted, PresentationContext pc, Dimse dimse, Attributes rq,
            Attributes data) throws IOException {
        Association asInvoked = (Association) asAccepted.getProperty(ProxyAEExtension.FORWARD_ASSOCIATION);
        if (asInvoked == null)
            try {
                processNActionRQForwardRules(asAccepted, pc, dimse, rq, data);
            } catch (ConfigurationException e) {
                LOG.warn(asAccepted + ": cannot process N-ACTION-RQ: " + e.getMessage());
                throw new DicomServiceException(Status.ProcessingFailure, e.getCause());
            }
        else
            try {
                @SuppressWarnings("unchecked")
                List<ForwardRule> fwrList = (List<ForwardRule>) asAccepted.getProperty(ProxyAEExtension.FORWARD_RULES);
                ForwardRule rule = fwrList.get(0);
                onNActionRQ(asAccepted, asInvoked, pc, rq, data, rule);
            } catch (Exception e) {
                LOG.debug(asAccepted + ": error forwarding N-ACTION-RQ: " + e.getMessage());
                if (LOG.isDebugEnabled())
                    e.printStackTrace();
                throw new DicomServiceException(Status.ProcessingFailure, e.getCause());
            }
    }

    private void processNActionRQForwardRules(Association as, PresentationContext pc, Dimse dimse, Attributes rq,
            Attributes data) throws IOException, ConfigurationException {
        ApplicationEntity ae = as.getApplicationEntity();
        ProxyAEExtension proxyAEE = ae.getAEExtension(ProxyAEExtension.class);
        List<ForwardRule> forwardRules = proxyAEE.filterForwardRulesOnDimseRQ(as, rq, dimse);
        if (forwardRules.size() == 0)
            throw new ConfigurationException("No matching forward rule");

        if (forwardRules.size() > 1)
            throw new ConfigurationException("More than one matching forward rule");

        ForwardRule rule = forwardRules.get(0);
        List<String> destinationAETs = proxyAEE.getDestinationAETsFromForwardRule(as, rule, data);
        switch (destinationAETs.size()) {
        case 0:
            throw new ConfigurationException("No destination");
        case 1: {
            String callingAET = (rule.getUseCallingAET() == null) ? as.getCallingAET() : rule.getUseCallingAET();
            processNActionForwardRule(proxyAEE, as, pc, dimse, rq, data, callingAET, destinationAETs.get(0), rule);
            break;
        }
        default:
            throw new ConfigurationException("More than one destination");
        }
    }

    private void processNActionForwardRule(ProxyAEExtension proxyAEE, Association asAccepted, PresentationContext pc,
            Dimse dimse, Attributes rq, Attributes data, String callingAET, String calledAET, ForwardRule rule)
            throws IOException, ConfigurationException {
        if (pendingFileForwarding(proxyAEE, calledAET, data)) {
            LOG.debug("{}: store N-ACTION-RQ for scheduled forwarding to {} due to pending C-STORE files", asAccepted, calledAET);
            createRQFile(proxyAEE, asAccepted, rq, data, ".dcm", callingAET, calledAET, proxyAEE.getNactionDirectoryPath());
            asAccepted.writeDimseRSP(pc, Commands.mkNActionRSP(rq, Status.Success), rq);
            return;
        }
        for (Entry<String, ForwardOption> entry : proxyAEE.getForwardOptions().entrySet()) {
            if (calledAET.equals(entry.getKey()) && !entry.getValue().getSchedule().isNow(new GregorianCalendar())) {
                storeNActionRQ(proxyAEE, asAccepted, pc, rq, data, ".dcm", callingAET, calledAET);
                return;
            }
        }
        try {
            AAssociateRQ rqCopy = proxyAEE.copyOf(asAccepted);
            rqCopy.setCallingAET(callingAET);
            rqCopy.setCalledAET(calledAET);
            Association asInvoked = proxyAEE.getApplicationEntity().connect(aeCache.findApplicationEntity(calledAET),
                    rqCopy);
            asAccepted.setProperty(ProxyAEExtension.FORWARD_ASSOCIATION, asInvoked);
            asInvoked.setProperty(ProxyAEExtension.FORWARD_ASSOCIATION, asAccepted);
            asInvoked.setProperty(ProxyAEExtension.FORWARD_RULE, rule);
            onNActionRQ(asAccepted, asInvoked, pc, rq, data, rule);
        } catch (GeneralSecurityException e) {
            LOG.error("{}: Failed to create SSL context: {}", asAccepted, e.getMessage());
            storeNActionRQ(proxyAEE, asAccepted, pc, rq, data, RetryObject.ConnectionException.getSuffix() + "0", callingAET, calledAET);
        } catch (Exception e) {
            LOG.error("{}: Error connecting to {}: {}", new Object[] { asAccepted, calledAET, e.getMessage() });
            storeNActionRQ(proxyAEE, asAccepted, pc, rq, data, RetryObject.ConnectionException.getSuffix() + "0", callingAET, calledAET);
        }
    }

    private void storeNActionRQ(ProxyAEExtension proxyAEE, Association asAccepted, PresentationContext pc,
            Attributes rq, Attributes data, String suffix, String callingAET, String calledAET) throws IOException {
        if (proxyAEE.isAcceptDataOnFailedAssociation()) {
            createRQFile(proxyAEE, asAccepted, rq, data, suffix, callingAET, calledAET, proxyAEE.getNactionDirectoryPath());
            asAccepted.writeDimseRSP(pc, Commands.mkNActionRSP(rq, Status.Success), rq);
        } else {
            asAccepted.writeDimseRSP(pc, Commands.mkNActionRSP(rq, Status.ProcessingFailure), rq);
        }
    }

    private void processNEventReportRQ(Association asAccepted, PresentationContext pc, Dimse dimse, Attributes rq,
            Attributes data) throws IOException {
        ProxyAEExtension proxyAEE = (ProxyAEExtension) asAccepted.getApplicationEntity().getAEExtension(
                ProxyAEExtension.class);
        String transactionUID = data.getString(Tag.TransactionUID);
        File nactionRqFile = getNactionRQFile(proxyAEE, transactionUID, asAccepted.getRemoteAET());
        if (nactionRqFile == null || !nactionRqFile.exists()) {
            LOG.debug(asAccepted + ": failed to load Transaction UID mapping for N-EVENT-REPORT-RQ from "
                    + asAccepted.getCallingAET());
            abortForward(pc, asAccepted, Commands.mkNEventReportRSP(rq, Status.InvalidArgumentValue));
            return;
        }
        if (hasOutstandingNEventReportRQs(proxyAEE, transactionUID, asAccepted.getRemoteAET()))
            storeNEventReportRQ(proxyAEE, asAccepted, pc, rq, data, transactionUID);
        else
            forwardNEventReportRQFromDestinationAET(proxyAEE, asAccepted, pc, rq, data, nactionRqFile);
    }

    private void storeNEventReportRQ(ProxyAEExtension proxyAEE, Association asAccepted, PresentationContext pc,
            Attributes rq, Attributes data, String transactionUID) throws IOException {
        createRQFile(proxyAEE, asAccepted, rq, data, ".nevent", null, asAccepted.getRemoteAET(), proxyAEE.getNeventDirectoryPath());
        asAccepted.writeDimseRSP(pc, Commands.mkNEventReportRSP(rq, Status.Success));
    }

    private File getNactionRQFile(ProxyAEExtension proxyAEE, String tsUID, String callingAET) throws IOException {
        for (String tsUIDDir : proxyAEE.getNeventDirectoryPath().list()) {
            if (tsUIDDir.equals(tsUID)) {
                File dir = new File(proxyAEE.getNeventDirectoryPath(), tsUID + ProxyAEExtension.getSeparator() + callingAET);
                if (dir.exists()) {
                    String[] files = dir.list(nactionFileFilter());
                    if (files.length == 0)
                        LOG.error("{}: no naction file in {}", this, dir.getPath());
                    else if (files.length > 1)
                        LOG.error("{}: cannot get naction file: too many files with suffix *.dcm in {}", this, dir.getPath());
                    else
                        return new File(dir, files[0]);
                }
            }
        }
        return null;
    }

    private boolean hasOutstandingNEventReportRQs(ProxyAEExtension proxyAEE, String transactionUID,
            String remoteAET) throws IOException {
        File transUidDir = new File (proxyAEE.getNeventDirectoryPath(), transactionUID);
        for (String calledAET : transUidDir.list()) {
            if (calledAET.equals(remoteAET))
                continue;
            File calledAETDir = new File(transactionUID, calledAET);
            File[] files = calledAETDir.listFiles(neventFileFilter());
            if (files.length < 1)
                return true;
        }
        return false;
    }

    private FilenameFilter neventFileFilter() {
        return new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".nevent");
            }
        };
    }

    private FilenameFilter nactionFileFilter() {
        return new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".naction");
            }
        };
    }

    private boolean pendingFileForwarding(ProxyAEExtension proxyAEE, String destinationAET, Attributes eventInfo)
            throws IOException {
        File dir = new File(proxyAEE.getCStoreDirectoryPath(), destinationAET);
        if (!dir.exists())
            return false;

        String[] files = dir.list();
        Sequence referencedSOPSequence = eventInfo.getSequence(Tag.ReferencedSOPSequence);
        Iterator<Attributes> it = referencedSOPSequence.iterator();
        while (it.hasNext()) {
            Attributes item = it.next();
            String referencedSOPInstanceUID = item.getString(Tag.ReferencedSOPInstanceUID);
            for (String file : files)
                if (file.startsWith(referencedSOPInstanceUID))
                    return true;
        }
        return false;
    }

    private void forwardNEventReportRQFromDestinationAET(ProxyAEExtension proxyAEE, Association asAccepted,
            PresentationContext pc, Attributes data, final Attributes eventInfo, File file) {
        String calledAEString = null;
        try {
            Properties prop = proxyAEE.getFileInfoProperties(file);
            calledAEString = prop.getProperty("source-aet");
            ApplicationEntity ae = asAccepted.getApplicationEntity();
            ApplicationEntity calledAE = aeCache.findApplicationEntity(calledAEString);
            AAssociateRQ rqCopy = proxyAEE.copyOf(asAccepted);
            rqCopy.setCalledAET(calledAEString);
            if (!ae.getAETitle().equals("*"))
                rqCopy.setCallingAET(ae.getAETitle());
            rqCopy.addRoleSelection(new RoleSelection(UID.StorageCommitmentPushModelSOPClass, true, true));
            Association asInvoked = ae.connect(calledAE, rqCopy);
            asInvoked.setProperty(ProxyAEExtension.FORWARD_ASSOCIATION, asAccepted);
            asAccepted.setProperty(ProxyAEExtension.FORWARD_ASSOCIATION, asInvoked);
            onNEventReportRQ(asAccepted, asInvoked, pc, data, eventInfo, file);
        } catch (AAssociateRJ e) {
            LOG.error(asAccepted + ": rejected association to forward AET: " + e.getReason());
            if (LOG.isDebugEnabled())
                e.printStackTrace();
            abortForward(pc, asAccepted, Commands.mkNEventReportRSP(data, Status.Success));
        } catch (IOException e) {
            LOG.error("{}: error connecting to {}: {}", new Object[] { asAccepted, calledAEString, e.getMessage() });
            abortForward(pc, asAccepted, Commands.mkNEventReportRSP(data, Status.Success));
        } catch (ConfigurationException e) {
            LOG.error("{}: error loading AET {} from configuration: {}",
                    new Object[] { asAccepted, calledAEString, e.getMessage() });
            abortForward(pc, asAccepted, Commands.mkNEventReportRSP(data, Status.Success));
        } catch (InterruptedException e) {
            LOG.error("{}: {}", asAccepted, e.getMessage());
            abortForward(pc, asAccepted, Commands.mkNEventReportRSP(data, Status.Success));
        } catch (IncompatibleConnectionException e) {
            LOG.error("{}: incompatible connection to forward AET {}: {}",
                    new Object[] { asAccepted, calledAEString, e.getMessage() });
            abortForward(pc, asAccepted, Commands.mkNEventReportRSP(data, Status.Success));
        } catch (GeneralSecurityException e) {
            LOG.error("{}: error creating SSL context: {}", asAccepted, e.getMessage());
            abortForward(pc, asAccepted, Commands.mkNEventReportRSP(data, Status.Success));
        }
    }

    private void abortForward(PresentationContext pc, Association asAccepted, Attributes response) {
        try {
            asAccepted.writeDimseRSP(pc, response);
        } catch (IOException e) {
            LOG.error(asAccepted + ": error forwarding storage commitment: " + e.getMessage());
            if (LOG.isDebugEnabled())
                e.printStackTrace();
        }
    }

    private void onNEventReportRQ(final Association asAccepted, Association asInvoked, final PresentationContext pc,
            Attributes rq, Attributes data, final File file) throws IOException, InterruptedException {
        String tsuid = pc.getTransferSyntax();
        String cuid = rq.getString(Tag.AffectedSOPClassUID);
        String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
        int eventTypeId = rq.getInt(Tag.EventTypeID, 0);
        int msgId = rq.getInt(Tag.MessageID, 0);
        DimseRSPHandler rspHandler = new DimseRSPHandler(msgId) {
            @Override
            public void onDimseRSP(Association asInvoked, Attributes cmd, Attributes data) {
                super.onDimseRSP(asInvoked, cmd, data);
                try {
                    asAccepted.writeDimseRSP(pc, cmd, data);
                    deleteFile(asAccepted, file);
                } catch (IOException e) {
                    int status = cmd.getInt(Tag.Status, -1);
                    LOG.error("{}: failed to forward file {} with error status {}", new Object[] { asAccepted, file,
                            Integer.toHexString(status) + 'H' });
                    if (LOG.isDebugEnabled())
                        e.printStackTrace();
                }
            }
        };
        asInvoked.neventReport(cuid, iuid, eventTypeId, data,
                ProxyAEExtension.getMatchingTsuid(asInvoked, tsuid, cuid), rspHandler);
    }

    private void onNActionRQ(final Association asAccepted, final Association asInvoked, final PresentationContext pc,
            final Attributes rq, final Attributes data, ForwardRule rule) throws IOException, InterruptedException {
        int actionTypeId = rq.getInt(Tag.ActionTypeID, 0);
        String tsuid = pc.getTransferSyntax();
        String cuid = rq.getString(Tag.RequestedSOPClassUID);
        String iuid = rq.getString(Tag.RequestedSOPInstanceUID);
        int msgId = rq.getInt(Tag.MessageID, 0);
        final ProxyAEExtension proxyAEE = (ProxyAEExtension) asAccepted.getApplicationEntity().getAEExtension(
                ProxyAEExtension.class);
        final File file = createRQFile(proxyAEE, asAccepted, rq, data, ".snd", asInvoked.getCallingAET(),
                asInvoked.getCalledAET(), proxyAEE.getNactionDirectoryPath());
        DimseRSPHandler rspHandler = new DimseRSPHandler(msgId) {
            @Override
            public void onDimseRSP(Association as, Attributes cmd, Attributes rspData) {
                super.onDimseRSP(asInvoked, cmd, rspData);
                int status = cmd.getInt(Tag.Status, -1);
                String filePath = file.getPath();
                switch (status) {
                case Status.Success: {
                    ForwardRule rule = (ForwardRule) asInvoked.getProperty(ProxyAEExtension.FORWARD_RULE);
                    String proxyAET = proxyAEE.getApplicationEntity().getAETitle();
                    String callingAET = rule.getUseCallingAET();
                    if (callingAET != null && callingAET.equals(proxyAET)) {
                        // n-event-report-rq will come to this proxy AET, save n-action-rq
                        try {
                            String fileName = file.getName();
                            File destDir = new File(proxyAEE.getNeventDirectoryPath(),
                                    data.getString(Tag.TransactionUID) + ProxyAEExtension.getSeparator()
                                            + asInvoked.getRemoteAET());
                            destDir.mkdirs();
                            File dest = new File(destDir, fileName.substring(0, fileName.lastIndexOf('.')) + ".naction");
                            renameFile(asAccepted, file, filePath, destDir, dest);
                        } catch (IOException e) {
                            LOG.error("{}: could not save NEventReportRQ: {}", new Object[] { asAccepted, e });
                            if (LOG.isDebugEnabled())
                                e.printStackTrace();
                            break;
                        }
                    } else {
                        // n-event-report-rq will not come back to this proxy AET, don't need to save n-action-rq
                        LOG.debug("{}: delete forwarded N-ACTION-RQ due to Calling AET ({}) != Proxy AET ({})",
                                new Object[] { as, callingAET, proxyAET });
                        deleteFile(asAccepted, file);
                    }
                    try {
                        asAccepted.writeDimseRSP(pc, cmd, rspData);
                        File path = new File(file.getParent());
                        if (path.list().length == 0)
                            path.delete();
                    } catch (IOException e) {
                        LOG.error(asAccepted + ": failed to forward N-ACTION-RSP: " + e.getMessage());
                        if (LOG.isDebugEnabled())
                            e.printStackTrace();
                    }
                    break;
                }
                default: {
                    LOG.warn("{}: failed to forward N-ACTION file {} with error status {}", new Object[] { asAccepted,
                            file, Integer.toHexString(status) + 'H' });
                    File error = new File(filePath + '.' + Integer.toHexString(status) + 'H');
                    if (file.renameTo(error))
                        LOG.debug("{}: RENAME {} to {}", new Object[] { asAccepted, filePath, error.getPath() });
                    else
                        LOG.debug("{}: failed to RENAME {} to {}",
                                new Object[] { asAccepted, filePath, error.getPath() });
                    try {
                        asAccepted.writeDimseRSP(pc, cmd);
                    } catch (IOException e) {
                        LOG.error(asAccepted + ": Failed to forward N-ACTION-RSP: " + e.getMessage());
                        if (LOG.isDebugEnabled())
                            e.printStackTrace();
                    }
                }
                }
            }
        };
        asInvoked.naction(cuid, iuid, actionTypeId, data, ProxyAEExtension.getMatchingTsuid(asInvoked, tsuid, cuid),
                rspHandler);
    }

    protected File createRQFile(ProxyAEExtension proxyAEE, Association asAccepted, Attributes rq,
            Attributes data, String suffix, String callingAET, String calledAET, File parent) throws IOException {
        String tsUID = data.getString(Tag.TransactionUID);
        File dir = new File(parent, tsUID + ProxyAEExtension.getSeparator() + calledAET);
        if (dir.mkdirs())
            LOG.debug("{}: created dir {}", asAccepted, dir);
        else {
            LOG.error("{}: could not create dir {}", asAccepted, dir);
            throw new DicomServiceException(Status.ProcessingFailure);
        }
        File file = File.createTempFile("dcm", suffix, dir);
        DicomOutputStream stream = null;
        try {
            stream = new DicomOutputStream(file);
            String iuid = UID.StorageCommitmentPushModelSOPInstance;
            String cuid = UID.StorageCommitmentPushModelSOPClass;
            String tsuid = UID.ExplicitVRLittleEndian;
            Attributes fmi = Attributes.createFileMetaInformation(iuid, cuid, tsuid);
            fmi.setString(Tag.SourceApplicationEntityTitle, VR.AE, asAccepted.getCallingAET());
            LOG.debug("{}: create {}", asAccepted, file.getPath());
            stream.writeDataset(fmi, data);
        } catch (Exception e) {
            LOG.error(asAccepted + ": Failed to create transaction UID file: " + e.getMessage());
            if (LOG.isDebugEnabled())
                e.printStackTrace();
            file.delete();
            throw new DicomServiceException(Status.OutOfResources, e.getCause());
        } finally {
            stream.close();
        }
        Properties prop = new Properties();
        prop.setProperty("source-aet", asAccepted.getCallingAET());
        if (callingAET != null)
            prop.setProperty("use-calling-aet", callingAET);
        prop.setProperty("sop-instance-uid", rq.getString(Tag.RequestedSOPInstanceUID));
        prop.setProperty("sop-class-uid", rq.getString(Tag.RequestedSOPClassUID));
        String path = file.getPath();
        File info = new File(path.substring(0, path.lastIndexOf('.')) + ".info");
        FileOutputStream infoOut = new FileOutputStream(info);
        try {
            LOG.debug("{}: create {}", asAccepted, info.getPath());
            prop.store(infoOut, null);
        } catch (Exception e) {
            LOG.error(asAccepted + ": Failed to create transaction UID info-file: " + e.getMessage());
            if (LOG.isDebugEnabled())
                e.printStackTrace();
            file.delete();
            info.delete();
            throw new DicomServiceException(Status.OutOfResources, e.getCause());
        } finally {
            infoOut.close();
        }
        return file;
    }

    private void deleteFile(Association as, File file) {
        if (file.delete())
            LOG.debug("{}: DELETE {}", new Object[] { as, file });
        else {
            LOG.error("{}: failed to DELETE {}", new Object[] { as, file });
        }
        File info = new File(file.getPath().substring(0, file.getPath().lastIndexOf('.')) + ".info");
        if (info.delete())
            LOG.debug("{}: DELETE {}", as, info);
        else
            LOG.error("{}: failed to DELETE {}", as, info);
    }

    private void renameFile(final Association asAccepted, final File file, String filePath, File destDir, File dest) {
        if (file.renameTo(dest)) {
            dest.setLastModified(System.currentTimeMillis());
            LOG.debug("{}: RENAME {} to {}",
                    new Object[] { asAccepted, file.getPath(), dest.getPath() });
            File infoFile = new File(filePath.substring(0, filePath.lastIndexOf('.')) + ".info");
            File infoFileDest = new File(destDir, infoFile.getName());
            if (infoFile.renameTo(infoFileDest))
                LOG.debug("{}: RENAME {} to {}", new Object[] { asAccepted, infoFile.getPath(),
                        infoFileDest.getPath() });
            else
                LOG.error("{}: failed to RENAME {} to {}",
                        new Object[] { asAccepted, infoFile.getPath(), infoFileDest.getPath() });
        } else
            LOG.error("{}: failed to RENAME {} to {}",
                    new Object[] { asAccepted, file.getPath(), dest.getPath() });
    }
}
