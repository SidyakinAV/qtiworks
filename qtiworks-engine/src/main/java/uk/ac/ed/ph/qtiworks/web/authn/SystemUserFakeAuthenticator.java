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
package uk.ac.ed.ph.qtiworks.web.authn;

import uk.ac.ed.ph.qtiworks.domain.entities.SystemUser;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;

/**
 * Implementation of {@link AbstractSystemUserAuthenticator} performing "fake" authentication
 *
 * @author David McKain
 */
public final class SystemUserFakeAuthenticator extends AbstractSystemUserAuthenticator {

    private static final Logger logger = LoggerFactory.getLogger(SystemUserFakeAuthenticator.class);

    /** Login Name for the assumed User */
    private final String fakeLoginName;

    public SystemUserFakeAuthenticator(final WebApplicationContext webApplicationContext, final String fakeLoginName) {
        super(webApplicationContext);
        this.fakeLoginName = fakeLoginName;
    }

    @Override
    protected SystemUser doAuthentication(final HttpServletRequest httpServletRequest, final HttpServletResponse httpServletResponse) {
        return lookupFakeUser();
    }

    private SystemUser lookupFakeUser() {
        final SystemUser user = systemUserDao.findByLoginName(fakeLoginName);
        if (user==null) {
            logger.warn("Could not find specified fake SystemUser with loginName {}", fakeLoginName);
            return null;
        }
        else if (user.isLoginDisabled()) {
            logger.warn("Fake SystemUser {} has their account marked as disabled", fakeLoginName);
            return null;
        }
        return user;
    }
}
