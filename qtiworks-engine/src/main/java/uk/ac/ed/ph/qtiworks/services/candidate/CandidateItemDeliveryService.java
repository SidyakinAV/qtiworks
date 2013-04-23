/* Copyright (c) 2012-2013, University of Edinburgh.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice, this
 *   list of conditions and the following disclaimer in the documentation and/or
 *   other materials provided with the distribution.
 *
 * * Neither the name of the University of Edinburgh nor the names of its
 *   contributors may be used to endorse or promote products derived from this
 *   software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *
 * This software is derived from (and contains code from) QTItools and MathAssessEngine.
 * QTItools is (c) 2008, University of Southampton.
 * MathAssessEngine is (c) 2010, University of Edinburgh.
 */
package uk.ac.ed.ph.qtiworks.services.candidate;

import uk.ac.ed.ph.qtiworks.QtiWorksLogicException;
import uk.ac.ed.ph.qtiworks.domain.DomainEntityNotFoundException;
import uk.ac.ed.ph.qtiworks.domain.IdentityContext;
import uk.ac.ed.ph.qtiworks.domain.RequestTimestampContext;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateEvent;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateFileSubmission;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateItemEventType;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateResponse;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateSession;
import uk.ac.ed.ph.qtiworks.domain.entities.Delivery;
import uk.ac.ed.ph.qtiworks.domain.entities.ItemDeliverySettings;
import uk.ac.ed.ph.qtiworks.domain.entities.ResponseLegality;
import uk.ac.ed.ph.qtiworks.services.CandidateAuditLogger;
import uk.ac.ed.ph.qtiworks.services.CandidateDataServices;
import uk.ac.ed.ph.qtiworks.services.CandidateSessionStarter;
import uk.ac.ed.ph.qtiworks.services.dao.CandidateResponseDao;
import uk.ac.ed.ph.qtiworks.services.dao.CandidateSessionDao;

import uk.ac.ed.ph.jqtiplus.internal.util.Assert;
import uk.ac.ed.ph.jqtiplus.node.AssessmentObjectType;
import uk.ac.ed.ph.jqtiplus.node.item.AssessmentItem;
import uk.ac.ed.ph.jqtiplus.notification.NotificationLevel;
import uk.ac.ed.ph.jqtiplus.notification.NotificationRecorder;
import uk.ac.ed.ph.jqtiplus.running.ItemSessionController;
import uk.ac.ed.ph.jqtiplus.state.ItemSessionState;
import uk.ac.ed.ph.jqtiplus.types.FileResponseData;
import uk.ac.ed.ph.jqtiplus.types.Identifier;
import uk.ac.ed.ph.jqtiplus.types.ResponseData;
import uk.ac.ed.ph.jqtiplus.types.StringResponseData;

import java.io.File;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service the manages the real-time delivery of a standalone {@link AssessmentItem}
 * to a candidate.
 * <p>
 * NOTE: Current single item delivery assumes that items are always presented immediately after
 * template processing runs, and that attempts are always treated as being submitted.
 * <p>
 * NOTE: Remember there is no {@link IdentityContext} for candidates.
 *
 * @author David McKain
 *
 * @see CandidateSessionStarter
 */
@Service
@Transactional(propagation=Propagation.REQUIRED)
public class CandidateItemDeliveryService {

    @Resource
    private RequestTimestampContext requestTimestampContext;

    @Resource
    private CandidateAuditLogger candidateAuditLogger;

    @Resource
    private CandidateDataServices candidateDataServices;

    @Resource
    private CandidateUploadService candidateUploadService;

    @Resource
    private CandidateSessionDao candidateSessionDao;

    @Resource
    private CandidateResponseDao candidateResponseDao;

    //----------------------------------------------------
    // Session access

    /**
     * Looks up the {@link CandidateSession} having the given ID (xid)
     * and checks the given sessionToken against that stored in the session as a means of
     * "authentication".
     *
     * @param xid
     *
     * @throws DomainEntityNotFoundException
     * @throws CandidateForbiddenException
     */
    public CandidateSession lookupCandidateItemSession(final long xid, final String sessionToken)
            throws DomainEntityNotFoundException, CandidateForbiddenException {
        Assert.notNull(sessionToken, "sessionToken");
        final CandidateSession candidateSession = candidateSessionDao.requireFindById(xid);
        if (!sessionToken.equals(candidateSession.getSessionToken())) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.ACCESS_CANDIDATE_SESSION);
        }
        if (candidateSession.getDelivery().getAssessment().getAssessmentType()!=AssessmentObjectType.ASSESSMENT_ITEM) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.ACCESS_CANDIDATE_SESSION_AS_ITEM);
        }
        return candidateSession;
    }

    private void ensureSessionNotTerminated(final CandidateSession candidateSession) throws CandidateForbiddenException {
        if (candidateSession.isTerminated()) {
            /* No access when session has been is closed */
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.ACCESS_TERMINATED_SESSION);
        }
    }

    //----------------------------------------------------
    // Response handling

    public void handleResponses(final long xid, final String sessionToken,
            final Map<Identifier, StringResponseData> stringResponseMap,
            final Map<Identifier, MultipartFile> fileResponseMap,
            final String candidateComment)
            throws CandidateForbiddenException, DomainEntityNotFoundException {
        final CandidateSession candidateSession = lookupCandidateItemSession(xid, sessionToken);
        handleResponses(candidateSession, stringResponseMap, fileResponseMap, candidateComment);
    }

    /**
     * @param candidateComment optional candidate comment, or null if no comment has been sent
     *
     * @throws CandidateForbiddenException
     */
    public void handleResponses(final CandidateSession candidateSession,
            final Map<Identifier, StringResponseData> stringResponseMap,
            final Map<Identifier, MultipartFile> fileResponseMap,
            final String candidateComment)
            throws CandidateForbiddenException {
        Assert.notNull(candidateSession, "candidateSession");
        final ItemDeliverySettings itemDeliverySettings = (ItemDeliverySettings) candidateSession.getDelivery().getDeliverySettings();

        /* Set up listener to record any notifications from JQTI candidateAuditLogger.logic */
        final NotificationRecorder notificationRecorder = new NotificationRecorder(NotificationLevel.INFO);

        /* Get current JQTI state and create JQTI controller */
        final CandidateEvent mostRecentEvent = candidateDataServices.getMostRecentEvent(candidateSession);
        final ItemSessionController itemSessionController = candidateDataServices.createItemSessionController(mostRecentEvent, notificationRecorder);

        /* Make sure an attempt is allowed */
        final ItemSessionState itemSessionState = itemSessionController.getItemSessionState();
        if (itemSessionState.isEnded()) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.MAKE_RESPONSES);
        }

        /* Make sure candidate may comment (if set) */
        if (candidateComment!=null && !itemDeliverySettings.isAllowCandidateComment()) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.SUBMIT_COMMENT);
        }

        /* Build response map in required format for JQTI+.
         * NB: The following doesn't test for duplicate keys in the two maps. I'm not sure
         * it's worth the effort.
         */
        final Map<Identifier, ResponseData> responseDataMap = new HashMap<Identifier, ResponseData>();
        if (stringResponseMap!=null) {
            for (final Entry<Identifier, StringResponseData> stringResponseEntry : stringResponseMap.entrySet()) {
                final Identifier identifier = stringResponseEntry.getKey();
                final StringResponseData stringResponseData = stringResponseEntry.getValue();
                responseDataMap.put(identifier, stringResponseData);
            }
        }
        final Map<Identifier, CandidateFileSubmission> fileSubmissionMap = new HashMap<Identifier, CandidateFileSubmission>();
        if (fileResponseMap!=null) {
            for (final Entry<Identifier, MultipartFile> fileResponseEntry : fileResponseMap.entrySet()) {
                final Identifier identifier = fileResponseEntry.getKey();
                final MultipartFile multipartFile = fileResponseEntry.getValue();
                if (!multipartFile.isEmpty()) {
                    final CandidateFileSubmission fileSubmission = candidateUploadService.importFileSubmission(candidateSession, multipartFile);
                    final FileResponseData fileResponseData = new FileResponseData(new File(fileSubmission.getStoredFilePath()), fileSubmission.getContentType(), fileSubmission.getFileName());
                    responseDataMap.put(identifier, fileResponseData);
                    fileSubmissionMap.put(identifier, fileSubmission);
                }
            }
        }

        /* Build Map of responses in appropriate entity form.
         * NB: Not ready for persisting yet. */
        final Map<Identifier, CandidateResponse> candidateResponseMap = new HashMap<Identifier, CandidateResponse>();
        for (final Entry<Identifier, ResponseData> responseEntry : responseDataMap.entrySet()) {
            final Identifier responseIdentifier = responseEntry.getKey();
            final ResponseData responseData = responseEntry.getValue();

            final CandidateResponse candidateResponse = new CandidateResponse();
            candidateResponse.setResponseIdentifier(responseIdentifier.toString());
            candidateResponse.setResponseDataType(responseData.getType());
            candidateResponse.setResponseLegality(ResponseLegality.VALID); /* (May change this below) */
            switch (responseData.getType()) {
                case STRING:
                    candidateResponse.setStringResponseData(((StringResponseData) responseData).getResponseData());
                    break;

                case FILE:
                    candidateResponse.setFileSubmission(fileSubmissionMap.get(responseIdentifier));
                    break;

                default:
                    throw new QtiWorksLogicException("Unexpected switch case: " + responseData.getType());
            }
            candidateResponseMap.put(responseIdentifier, candidateResponse);
        }

        /* Submit comment (if provided)
         * NB: Do this first in case next actions end the item session.
         */
        final Date timestamp = requestTimestampContext.getCurrentRequestTimestamp();
        if (candidateComment!=null) {
            itemSessionController.setCandidateComment(timestamp, candidateComment);
        }

        /* Attempt to bind responses */
        itemSessionController.bindResponses(timestamp, responseDataMap);

        /* Note any responses that failed to bind */
        final Set<Identifier> badResponseIdentifiers = itemSessionState.getUnboundResponseIdentifiers();
        final boolean allResponsesBound = badResponseIdentifiers.isEmpty();
        for (final Identifier badResponseIdentifier : badResponseIdentifiers) {
            candidateResponseMap.get(badResponseIdentifier).setResponseLegality(ResponseLegality.BAD);
        }

        /* Now validate the responses according to any constraints specified by the interactions */
        boolean allResponsesValid = false;
        if (allResponsesBound) {
            final Set<Identifier> invalidResponseIdentifiers = itemSessionState.getInvalidResponseIdentifiers();
            allResponsesValid = invalidResponseIdentifiers.isEmpty();
            if (!allResponsesValid) {
                /* Some responses not valid, so note these down */
                for (final Identifier invalidResponseIdentifier : invalidResponseIdentifiers) {
                    candidateResponseMap.get(invalidResponseIdentifier).setResponseLegality(ResponseLegality.INVALID);
                }
            }
        }

        /* (We commit responses immediately here) */
        itemSessionController.commitResponses(timestamp);

        /* Invoke response processing (only if responses are valid) */
        if (allResponsesValid) {
            itemSessionController.performResponseProcessing(timestamp);
        }

        /* Record resulting attempt and event */
        final CandidateItemEventType eventType = allResponsesBound ?
            (allResponsesValid ? CandidateItemEventType.ATTEMPT_VALID : CandidateItemEventType.RESPONSE_INVALID)
            : CandidateItemEventType.RESPONSE_BAD;
        final CandidateEvent candidateEvent = candidateDataServices.recordCandidateItemEvent(candidateSession,
                eventType, itemSessionState, notificationRecorder);
        candidateAuditLogger.logCandidateEvent(candidateEvent);

        /* Link and persist CandidateResponse entities */
        for (final CandidateResponse candidateResponse : candidateResponseMap.values()) {
            candidateResponse.setCandidateEvent(candidateEvent);
            candidateResponseDao.persist(candidateResponse);
        }

        /* Check whether processing wants to close the session and persist state */
        if (itemSessionState.isEnded()) {
            candidateDataServices.computeAndRecordItemAssessmentResult(candidateSession, itemSessionController);
            candidateSession.setClosed(true);
        }
        candidateSessionDao.update(candidateSession);
    }

    //----------------------------------------------------
    // Session close(by candidate)

    /**
     * Closes the {@link CandidateSession} having the given ID (xid), moving it
     * into closed state.
     */
    public CandidateSession closeCandidateSession(final long xid, final String sessionToken)
            throws CandidateForbiddenException, DomainEntityNotFoundException {
        final CandidateSession candidateSession = lookupCandidateItemSession(xid, sessionToken);
        return closeCandidateSession(candidateSession);
    }

    public CandidateSession closeCandidateSession(final CandidateSession candidateSession)
            throws CandidateForbiddenException {
        Assert.notNull(candidateSession, "candidateSession");

        /* Get current session state */
        final ItemSessionState itemSessionState = candidateDataServices.computeCurrentItemSessionState(candidateSession);

        /* Check this is allowed in current state */
        ensureSessionNotTerminated(candidateSession);
        final Delivery delivery = candidateSession.getDelivery();
        final ItemDeliverySettings itemDeliverySettings = (ItemDeliverySettings) delivery.getDeliverySettings();
        if (itemSessionState.isEnded()) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.CLOSE_SESSION_WHEN_CLOSED);
        }
        else if (!itemDeliverySettings.isAllowClose()) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.CLOSE_SESSION_WHEN_INTERACTING);
        }

        /* Update state */
        final Date timestamp = requestTimestampContext.getCurrentRequestTimestamp();
        final NotificationRecorder notificationRecorder = new NotificationRecorder(NotificationLevel.INFO);
        final ItemSessionController itemSessionController = candidateDataServices.createItemSessionController(delivery,
                itemSessionState, notificationRecorder);
        itemSessionController.endItem(timestamp);

        /* Record and log event */
        final CandidateEvent candidateEvent = candidateDataServices.recordCandidateItemEvent(candidateSession,
                CandidateItemEventType.CLOSE, itemSessionState, notificationRecorder);
        candidateAuditLogger.logCandidateEvent(candidateEvent);

        /* Update session state and record result */
        candidateDataServices.computeAndRecordItemAssessmentResult(candidateSession, itemSessionController);
        candidateSession.setClosed(true);
        candidateSessionDao.update(candidateSession);

        return candidateSession;
    }

    //----------------------------------------------------
    // Session reinit

    /**
     * Re-initialises the {@link CandidateSession} having the given ID (xid), returning the
     * updated {@link CandidateSession}. At QTI level, this reruns template processing, so
     * randomised values will change as a result of this process.
     */
    public CandidateSession resetCandidateSessionHard(final long xid, final String sessionToken)
            throws CandidateForbiddenException, DomainEntityNotFoundException {
        final CandidateSession candidateSession = lookupCandidateItemSession(xid, sessionToken);
        return resetCandidateSessionHard(candidateSession);
    }

    public CandidateSession resetCandidateSessionHard(final CandidateSession candidateSession)
            throws CandidateForbiddenException {
        Assert.notNull(candidateSession, "candidateSession");

        /* Get current session state */
        final ItemSessionState itemSessionState = candidateDataServices.computeCurrentItemSessionState(candidateSession);

        /* Make sure caller may reinit the session */
        ensureSessionNotTerminated(candidateSession);
        final Delivery delivery = candidateSession.getDelivery();
        final ItemDeliverySettings itemDeliverySettings = (ItemDeliverySettings) delivery.getDeliverySettings();
        if (!itemSessionState.isEnded() && !itemDeliverySettings.isAllowReinitWhenInteracting()) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.REINIT_SESSION_WHEN_INTERACTING);
        }
        else if (itemSessionState.isEnded() && !itemDeliverySettings.isAllowReinitWhenClosed()) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.REINIT_SESSION_WHEN_CLOSED);
        }

        /* Update state */
        final Date timestamp = requestTimestampContext.getCurrentRequestTimestamp();
        final NotificationRecorder notificationRecorder = new NotificationRecorder(NotificationLevel.INFO);
        final ItemSessionController itemSessionController = candidateDataServices.createItemSessionController(delivery,
                itemSessionState, notificationRecorder);
        itemSessionController.resetItemSessionHard(timestamp, true);

        /* Record and log event */
        final CandidateEvent candidateEvent = candidateDataServices.recordCandidateItemEvent(candidateSession,
                CandidateItemEventType.REINIT, itemSessionState, notificationRecorder);
        candidateAuditLogger.logCandidateEvent(candidateEvent);

        /* Update session depending on state after processing. Record final result if session closed immediately */
        candidateSession.setClosed(itemSessionState.isEnded());
        if (itemSessionState.isEnded()) {
            candidateDataServices.computeAndRecordItemAssessmentResult(candidateSession, itemSessionController);
        }
        candidateSessionDao.update(candidateSession);

        return candidateSession;
    }

    //----------------------------------------------------
    // Session reset

    /**
     * Resets the {@link CandidateSession} having the given ID (xid), returning the
     * updated {@link CandidateSession}. This takes the session back to the state it
     * was in immediately after the last {@link CandidateItemEventType#REINIT} (if applicable),
     * or after the original {@link CandidateItemEventType#INIT}.
     */
    public CandidateSession resetCandidateSessionSoft(final long xid, final String sessionToken)
            throws CandidateForbiddenException, DomainEntityNotFoundException {
        final CandidateSession candidateSession = lookupCandidateItemSession(xid, sessionToken);
        return resetCandidateSessionSoft(candidateSession);
    }

    public CandidateSession resetCandidateSessionSoft(final CandidateSession candidateSession)
            throws CandidateForbiddenException {
        Assert.notNull(candidateSession, "candidateSession");

        /* Get current session state */
        final ItemSessionState itemSessionState = candidateDataServices.computeCurrentItemSessionState(candidateSession);

        /* Make sure caller may reset the session */
        ensureSessionNotTerminated(candidateSession);
        final Delivery delivery = candidateSession.getDelivery();
        final ItemDeliverySettings itemDeliverySettings = (ItemDeliverySettings) delivery.getDeliverySettings();
        if (!itemSessionState.isEnded() && !itemDeliverySettings.isAllowResetWhenInteracting()) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.RESET_SESSION_WHEN_INTERACTING);
        }
        else if (itemSessionState.isEnded() && !itemDeliverySettings.isAllowResetWhenClosed()) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.RESET_SESSION_WHEN_CLOSED);
        }

        /* Update state */
        final Date timestamp = requestTimestampContext.getCurrentRequestTimestamp();
        final NotificationRecorder notificationRecorder = new NotificationRecorder(NotificationLevel.INFO);
        final ItemSessionController itemSessionController = candidateDataServices.createItemSessionController(delivery,
                itemSessionState, notificationRecorder);
        itemSessionController.resetItemSessionSoft(timestamp, true);

        /* Record and event */
        final CandidateEvent candidateEvent = candidateDataServices.recordCandidateItemEvent(candidateSession, CandidateItemEventType.RESET, itemSessionState);
        candidateAuditLogger.logCandidateEvent(candidateEvent);

        /* Update session depending on state after processing. Record final result if session closed immediately */
        candidateSession.setClosed(itemSessionState.isEnded());
        if (itemSessionState.isEnded()) {
            candidateDataServices.computeAndRecordItemAssessmentResult(candidateSession, itemSessionController);
        }
        candidateSessionDao.update(candidateSession);

        return candidateSession;
    }

    //----------------------------------------------------
    // Solution request

    /**
     * Logs a {@link CandidateItemEventType#SOLUTION} event, closing the item session if it hasn't
     * already been closed (and if this is allowed).
     */
    public CandidateSession requestSolution(final long xid, final String sessionToken)
            throws CandidateForbiddenException, DomainEntityNotFoundException {
        final CandidateSession candidateSession = lookupCandidateItemSession(xid, sessionToken);
        return requestSolution(candidateSession);
    }

    public CandidateSession requestSolution(final CandidateSession candidateSession)
            throws CandidateForbiddenException {
        Assert.notNull(candidateSession, "candidateSession");

        /* Get current session state */
        final ItemSessionState itemSessionState = candidateDataServices.computeCurrentItemSessionState(candidateSession);

        /* Make sure caller may do this */
        ensureSessionNotTerminated(candidateSession);
        final Delivery delivery = candidateSession.getDelivery();
        final ItemDeliverySettings itemDeliverySettings = (ItemDeliverySettings) delivery.getDeliverySettings();
        if (!itemSessionState.isEnded() && !itemDeliverySettings.isAllowSolutionWhenInteracting()) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.SOLUTION_WHEN_INTERACTING);
        }
        else if (itemSessionState.isEnded() && !itemDeliverySettings.isAllowResetWhenClosed()) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.SOLUTION_WHEN_CLOSED);
        }

        /* Close session if required */
        final Date timestamp = requestTimestampContext.getCurrentRequestTimestamp();
        final NotificationRecorder notificationRecorder = new NotificationRecorder(NotificationLevel.INFO);
        final ItemSessionController itemSessionController = candidateDataServices.createItemSessionController(delivery,
                itemSessionState, notificationRecorder);
        boolean isClosingSession = false;
        if (!itemSessionState.isEnded()) {
            isClosingSession = true;
            itemSessionController.endItem(timestamp);
        }

        /* Record and log event */
        final CandidateEvent candidateEvent = candidateDataServices.recordCandidateItemEvent(candidateSession, CandidateItemEventType.SOLUTION, itemSessionState);
        candidateAuditLogger.logCandidateEvent(candidateEvent);

        /* Update session if required */
        if (isClosingSession) {
            candidateSession.setClosed(true);
            candidateDataServices.computeAndRecordItemAssessmentResult(candidateSession, itemSessionController);
            candidateSessionDao.update(candidateSession);
        }

        return candidateSession;
    }

    //----------------------------------------------------
    // Session termination (by candidate)

    /**
     * Terminates the {@link CandidateSession} having the given ID (xid).
     * <p>
     * Currently we're always allowing this action to be made when in
     * interacting or closed states.
     */
    public CandidateSession terminateCandidateSession(final long xid, final String sessionToken)
            throws CandidateForbiddenException, DomainEntityNotFoundException {
        final CandidateSession candidateSession = lookupCandidateItemSession(xid, sessionToken);
        return terminateCandidateSession(candidateSession);
    }

    public CandidateSession terminateCandidateSession(final CandidateSession candidateSession)
            throws CandidateForbiddenException {
        Assert.notNull(candidateSession, "candidateSession");

        /* Check session has not already been terminated */
        final Delivery delivery = candidateSession.getDelivery();
        ensureSessionNotTerminated(candidateSession);

        /* Get current session state */
        final ItemSessionState itemSessionState = candidateDataServices.computeCurrentItemSessionState(candidateSession);

        /* Record and log event */
        final CandidateEvent candidateEvent = candidateDataServices.recordCandidateItemEvent(candidateSession,
                CandidateItemEventType.TERMINATE, itemSessionState);
        candidateAuditLogger.logCandidateEvent(candidateEvent);

        /* Are we terminating a session that hasn't been ended? If so, record the final result. */
        if (!itemSessionState.isEnded()) {
            final Date timestamp = requestTimestampContext.getCurrentRequestTimestamp();
            final NotificationRecorder notificationRecorder = new NotificationRecorder(NotificationLevel.INFO);
            final ItemSessionController itemSessionController = candidateDataServices.createItemSessionController(delivery,
                    itemSessionState, notificationRecorder);
            itemSessionController.endItem(timestamp);
            candidateDataServices.computeAndRecordItemAssessmentResult(candidateSession, itemSessionController);
        }

        /* Update session */
        candidateSession.setTerminated(true);
        candidateSessionDao.update(candidateSession);

        return candidateSession;
    }
}
