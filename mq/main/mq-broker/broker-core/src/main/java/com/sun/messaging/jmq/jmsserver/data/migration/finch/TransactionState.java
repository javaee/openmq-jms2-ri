/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2000-2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

/*
 * @(#)TransactionState.java	1.3 06/28/07
 */ 

package com.sun.messaging.jmq.jmsserver.data.migration.finch;

import java.io.*;
import java.util.Hashtable;
import javax.transaction.xa.XAResource;
import com.sun.messaging.jmq.util.JMQXid;
import com.sun.messaging.jmq.io.PacketType;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.data.AutoRollbackType;
import com.sun.messaging.jmq.jmsserver.util.BrokerException;
import com.sun.messaging.jmq.jmsserver.resources.BrokerResources;

/**
 * The state of a transaction, plus additional information that may
 * useful to know about the transaction.
 *
 * Object format prior to 370 filestore, use for migration purpose only.
 * @see com.sun.messaging.jmq.jmsserver.data.TransactionState
 */
public class TransactionState implements Serializable {

    static final long serialVersionUID = 4132677693277056907L;

    public static final int CREATED    = 0;
    public static final int STARTED    = 1;
    public static final int FAILED     = 2;
    public static final int INCOMPLETE = 3;
    public static final int COMPLETE   = 4;
    public static final int PREPARED   = 5;
    public static final int COMMITTED  = 6;
    public static final int ROLLEDBACK = 7;
    public static final int LAST = 7;

    private static final String names[] = {
        "CREATED",
        "STARTED",
        "FAILED",
        "INCOMPLETE",
        "COMPLETE",
        "PREPARED",
        "COMMITED",
        "ROLLEDBACK"
        };

    private JMQXid  xid = null;

    // State of the transaction
    private int state = CREATED;

    // User that created the transaction
    private String user = null;

    // Client ID of client that created transaction
    private String clientID = null;

    // A readable string that describes the connection this transaction
    // was created on
    private String connectionString = null;

    public TransactionState() {
        this.state = CREATED;
    }

    // Copy constructor;
    public TransactionState(TransactionState ts) {
        // Unfortunately JMQXid is mutable, so we must make a copy
        this.xid = new JMQXid(ts.xid);

        // Strings are immutable, so we just assign them
        this.state = ts.state;
        this.user = ts.user;
        this.clientID = ts.clientID;
        this.connectionString = ts.connectionString;
    }

    public Hashtable getDebugState() {
        Hashtable ht = new Hashtable();
        ht.put("xid", (xid == null ? "none" : xid.toString()));
        ht.put("state",names[state]);
        ht.put("user", (user == null ? "none" : user));
        ht.put("connectionString", (connectionString == null ? "none" : connectionString));
        ht.put("clientID", (clientID == null ? "none" : clientID));
        return ht;
    }

    public void setState(int state)
        throws BrokerException {
        if (state < CREATED || state > LAST) {
            // Internal error
            throw new BrokerException("Illegal state " +
                state + ". Should be between " + CREATED + " and " + LAST +
                    " inclusive.");
        } else {
            this.state = state;
        }
    }

    public int getState() {
        return state;
    }

    public void setXid(JMQXid xid) {
        this.xid = xid;
    }

    public JMQXid getXid() {
        return this.xid;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getUser() {
        return this.user;
    }

    public void setClientID(String id) {
        this.clientID = id;
    }

    public String getClientID() {
        return this.clientID;
    }

    public void setConnectionString(String s) {
        this.connectionString = s;
    }

    public String getConnectionString() {
        return this.connectionString;
    }

    /**
     * Returns the next state for this object, given an operation
     * and XAResource flag. For example if the current state is
     * STARTED and nextState is called with operation=END_TRANSACTION,
     * and xaFlag=XAResource.TMSUCCESS, then we return the state
     * COMPLETE.
     *
     * Throws an IllegalStateException if the state transition is not
     * allowed.
     *
     * Note that nextState does NOT alter the state of this object.
     * setState() must be called to do that.
     */
    public int nextState(int pktType, Integer xaFlag)
        throws BrokerException {

        switch (pktType) {

        case PacketType.START_TRANSACTION:
            if (isFlagSet(XAResource.TMNOFLAGS, xaFlag)) {
                if (this.state == CREATED ||
                    this.state == COMPLETE ||
                    this.state == STARTED) {
                    return STARTED;
                }
                break;
            } else if (isFlagSet(XAResource.TMJOIN, xaFlag)) {
                if (this.state == STARTED ||
                    this.state == COMPLETE) {
                    return STARTED;
                }
            } else if (isFlagSet(XAResource.TMRESUME, xaFlag)) {
                if (this.state == INCOMPLETE ||
                    this.state == STARTED) {
                    return STARTED;
                }
            }
            break;
        case PacketType.END_TRANSACTION:
            if (isFlagSet(XAResource.TMSUSPEND, xaFlag)) {
                if (this.state == STARTED ||
                    this.state == INCOMPLETE) {
                    return INCOMPLETE;
                }
            } else if (isFlagSet(XAResource.TMFAIL, xaFlag)) {
                if (this.state == STARTED ||
                    this.state == INCOMPLETE ||
                    this.state == FAILED) {
                    return FAILED;
                }
            } else if (isFlagSet(XAResource.TMSUCCESS, xaFlag) ||
                       isFlagSet(XAResource.TMONEPHASE, xaFlag)) {
                // XXX REVISIT 12/17/2001 dipol allow ONEPHASE since RI
                // appears to use it.
                if (this.state == STARTED ||
                    this.state == INCOMPLETE ||
                    this.state == COMPLETE) {
                    return COMPLETE;
                }
            }
            break;
        case PacketType.PREPARE_TRANSACTION:
            if (this.state == COMPLETE ||
                this.state == PREPARED) {
                return PREPARED;
            }
            break;
        case PacketType.COMMIT_TRANSACTION:
            if (isFlagSet(XAResource.TMONEPHASE, xaFlag)) {
                if (this.state == COMPLETE ||
                    this.state == COMMITTED) {
                    return COMMITTED;
                }
            } else {
                if (this.state == PREPARED ||
                    this.state == COMMITTED) {
                    return COMMITTED;
                }
            }
            break;
        case PacketType.ROLLBACK_TRANSACTION:
            if (this.state == COMPLETE || this.state == INCOMPLETE) {
                return ROLLEDBACK;
            } else if (this.state == PREPARED) {
                return ROLLEDBACK;
            } else if (this.state == FAILED || this.state == ROLLEDBACK) {
                return ROLLEDBACK;
            }
            break;
        }

        Object[] args = {PacketType.getString(pktType),
                         xaFlagToString(xaFlag),
                         this.toString(this.state)};

        throw new BrokerException(Globals.getBrokerResources().getString(
            BrokerResources.X_BAD_TXN_TRANSITION, args));
    }

    /**
     * Returns "true" if the specified flag is set in xaFlags, else
     * returns "false".
     */
    public static boolean isFlagSet(int flag, Integer xaFlags) {

        if (xaFlags == null) {
            return (flag == XAResource.TMNOFLAGS);
        } else if (flag == XAResource.TMNOFLAGS ||
                   xaFlags.intValue() == XAResource.TMNOFLAGS) {
            return (flag == xaFlags.intValue());
        } else {
            return ((xaFlags.intValue() & flag) == flag);
        }
    }

    public static String toString(int state) {
        if (state < CREATED || state > ROLLEDBACK) {
            return "UNKNOWN(" + state + ")";
        } else {
            return names[state] + "(" + state + ")";
        }
    }

    /**
     * Converts an XAFlag into a easily readable form
     */
    public static String xaFlagToString(Integer flags) {
        StringBuffer sb = new StringBuffer("");
        boolean found = false;

        if (flags == null) {
            return "null";
        }

        sb.append("0x" + Integer.toHexString(flags.intValue()) + ":");

        if (isFlagSet(XAResource.TMNOFLAGS, flags)) {
            sb.append("TMNOFLAGS");
            return sb.toString();
        }

        if (isFlagSet(XAResource.TMENDRSCAN, flags)) {
            sb.append("TMENDRSCAN");
            found = true;
        }
        if (isFlagSet(XAResource.TMFAIL, flags)) {
            if (found) sb.append("|");
            sb.append("TMFAIL");
            found = true;
        }
        if (isFlagSet(XAResource.TMJOIN, flags)) {
            if (found) sb.append("|");
            sb.append("TMJOIN");
            found = true;
        }
        if (isFlagSet(XAResource.TMONEPHASE, flags)) {
            if (found) sb.append("|");
            sb.append("TMONEPHASE");
            found = true;
        }
        if (isFlagSet(XAResource.TMRESUME, flags)) {
            if (found) sb.append("|");
            sb.append("TMRESUME");
            found = true;
        }
        if (isFlagSet(XAResource.TMSTARTRSCAN, flags)) {
            if (found) sb.append("|");
            sb.append("TMSTARTSCAN");
            found = true;
        }
        if (isFlagSet(XAResource.TMSUCCESS, flags)) {
            if (found) sb.append("|");
            sb.append("TMSUCCESS");
            found = true;
        }
        if (isFlagSet(XAResource.TMSUSPEND, flags)) {
            if (found) sb.append("|");
            sb.append("TMSUSPEND");
            found = true;
        }

        // Hmmm...we found no flags we know
        if (!found) {
            sb.append("???");
        }

        return sb.toString();
    }

    public String toString() {
        if (xid == null) {
            return user + "@" + clientID + ":" + toString(state);
        } else {
            return user + "@" + clientID + ":" + toString(state) +
                ":xid=" + xid.toString();
        }
    }

    public Object readResolve() throws ObjectStreamException {
        try {
            // Replace w/ the new object
            com.sun.messaging.jmq.jmsserver.data.TransactionState obj =
                new com.sun.messaging.jmq.jmsserver.data.TransactionState(
                    AutoRollbackType.NOT_PREPARED, 0, true);
            obj.setXid(xid);
            obj.setState(state);
            obj.setUser(user);
            obj.setClientID(clientID);
            obj.setConnectionString(connectionString);
            return obj;
        } catch (BrokerException ex) {
            ex.printStackTrace();
            return null;
        }
    }
}
