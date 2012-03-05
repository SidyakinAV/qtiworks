/* Copyright (c) 2012, University of Edinburgh.
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
package org.qtitools.qti.node.test.flow;

import uk.ac.ed.ph.jqtiplus.node.test.AssessmentItemRef;

/**
 * Item reference node is middle node with item reference object.
 * 
 * @author Jiri Kajaba
 */
public class ItemRefNode extends MiddleNode {

    private static final long serialVersionUID = 1509197895071292029L;

    private ItemRefNode prev;

    private ItemRefNode next;

    /**
     * Constructs node.
     * 
     * @param prev previous node in linked list
     * @param object assessment object (item reference)
     */
    public ItemRefNode(Node prev, AssessmentItemRef object) {
        super(prev, object);
    }

    @Override
    public boolean isItemRef() {
        return true;
    }

    /**
     * Gets assessment object (item reference) of this node.
     * 
     * @return assessment object (item reference) of this node
     */
    public AssessmentItemRef getItemRef() {
        return (AssessmentItemRef) getObject();
    }

    /**
     * Gets previous item reference node in linked list (can be null).
     * 
     * @return previous item reference node in linked list (can be null)
     * @see #setPrevItemRef
     */
    public ItemRefNode getPrevItemRef() {
        return prev;
    }

    /**
     * Sets new previous item reference node in linked list.
     * 
     * @param prev new previous item reference node in linked list
     * @see #getPrevItemRef
     */
    public void setPrevItemRef(ItemRefNode prev) {
        this.prev = prev;
    }

    /**
     * Gets next item reference node in linked list (can be null).
     * 
     * @return next item reference node in linked list (can be null)
     * @see #setNextItemRef
     */
    public ItemRefNode getNextItemRef() {
        return next;
    }

    /**
     * Sets new next item reference node in linked list.
     * 
     * @param next new next item reference node in linked list
     * @see #getNextItemRef
     */
    public void setNextItemRef(ItemRefNode next) {
        this.next = next;
    }
}