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
 * @(#)AckQueue.java	1.14 06/27/07
 */ 

package com.sun.messaging.jmq.jmsclient;

import java.io.PrintStream;
import java.util.logging.Level;

import com.sun.messaging.AdministeredObject;
import com.sun.messaging.jmq.io.PacketType;
import com.sun.messaging.jmq.io.ReadWritePacket;

/**
 * This Class is used by ProtocolHandler(for ack use).
 */

class AckQueue extends SessionQueue implements Traceable {

    // The interval before the first *waiting for response* messages
	// we double this interval before each subsequent message    
    protected static long INITIAL_LOG_INTERVAL = 120000; // 2 minutes
    
    //debug flag.
    private boolean debug = Debug.debug;

    //mq will dump pkt if wait for more than 3 iterations in the while loop.
    //This would be 120 + 240 + 360= 840 seconds.
    private static int DEFAULT_DUMP_COUNTER = 3;

    //dump connection state flag
    private static boolean dumpConnectionState = true;

    /**
     * A private static system property to change the default
     * wait time out to start printing "waiting for response"
     * message.  The default is set to 2 minutes.  This is
     * different from the wait timeout for broker response.
     * The wait timeout for broker response is set to 0 (forever)
     * by default (in ProtocolHandler).
     */
    static {

        try {
            //in milli secs.
            String prop = System.getProperty("imq.ackWaitTime");

            if (prop != null) {
                INITIAL_LOG_INTERVAL = Long.parseLong(prop);
            }

            String dump_prop = System.getProperty("imq.ackDumpCount");
            if (dump_prop != null) {
                DEFAULT_DUMP_COUNTER = Integer.parseInt(dump_prop);
            }

            boolean nostate = Boolean.getBoolean("imq.noConnectionState");
            if ( nostate ) {
                dumpConnectionState = false;
            }


        } catch (Exception e) {
            Debug.printStackTrace(e);
        }
    }

    public AckQueue() {
        super();
    }

    public AckQueue (boolean useSequential, int size) {
        super (useSequential, size);
    }
    
    /**
     * Wait for a reply from the broker to arrive on this AckQueue.

     * If the specified timeout is zero (no timeout) or is greater than 2 mins,
     * logging messages are generated after 2 mins and periodically afterwards
     * until a message arrives or the timeout is reached
     * 
     * @param conn The connection to the broker
     * @param pkt Used for logging only: The Packet for which we are awaiting a reply
     * @param timeout Total amount we are prepared to wait, or 0 if we are prepared to wait indefinitely
     * @return The reply packet
     */
	protected synchronized Object dequeueWait(ConnectionImpl conn, ReadWritePacket pkt, long timeout) {

		long totalElapsed = 0;
		long timeLeftBeforeTimeout = timeout;
		int icounter = 0;
		
		// calculate appropriate wait time 
		long lengthOfNextWait;
		if (timeout > 0 && timeout < INITIAL_LOG_INTERVAL) {
			lengthOfNextWait=timeout;
		} else {
			lengthOfNextWait=INITIAL_LOG_INTERVAL;
		}

		while (isEmpty() && (isClosed == false)) {

			/**
			 * resend is disabled due to bug ID 6551007: PREPARE_TRANSACTION pkt sent to broker again while waiting
			 * PREPARE_TRANSACTION_REPLY.
			 * 
			 * This feature would require broker to handle resend. Otherwise broker prints error messages and caused
			 * confustion.
			 */
			if (icounter > 0) {
				// resend (conn, pkt);
			}

			long waitStartedTime = System.currentTimeMillis();
			try {
				wait(lengthOfNextWait);
			} catch (InterruptedException e) {
				;
			}

			if (isEmpty() && (isClosed == false)) {
				// work out how much time elapsed since we started the wait 
				// (this may have been a spurious wakeup so it isn't necessarily lengthOfNextWait)
				long timeWeWaitedFor = System.currentTimeMillis()-waitStartedTime;
			
				// total elapsed time so far
				totalElapsed = totalElapsed + timeWeWaitedFor;
				
				if (timeWeWaitedFor<lengthOfNextWait){
					// wait() returned early with no message, so this was a spurious wakeup
					// go back to sleep for the remainder of the defined wait time
					lengthOfNextWait=lengthOfNextWait-timeWeWaitedFor;
					continue;
				}
								
				// bug 6189645 -- general blocking issues.
				if (shouldExit(conn)) {
					//For whatever reason that we end up blocking here, we should exit.
					return null;
				}

				// Calculate next wait time 
				icounter++;
				if (timeout==0){
					// no timeout
					// double the interval before we want the next log message
					lengthOfNextWait = INITIAL_LOG_INTERVAL * (1<<icounter);
				} else {
					// defined timeout
					// calculate how much time left until we want to timeout 
					timeLeftBeforeTimeout = timeout - totalElapsed;
					System.out.println("timeleft="+timeLeftBeforeTimeout);

					// we have more wait time.
					if (timeLeftBeforeTimeout > 0) {
						// double the interval before we want the next log message
						lengthOfNextWait = INITIAL_LOG_INTERVAL * (1<<icounter);
						 
						// use timeLeft if smaller than lengthOfNextWait
						if (timeLeftBeforeTimeout < lengthOfNextWait) {
							lengthOfNextWait = timeLeftBeforeTimeout;
						}
					} else {
						// timeout, exit.
					 	isClosed = true;
					}
				}
				
				if (!isClosed){
					// Get total time as secs in string format.
					String ts = String.valueOf(totalElapsed / 1000);

					// log a warning that no response has been received but we're still waiting
					printInfo(conn, pkt, ts);

					// we only print this once.
					if (icounter == DEFAULT_DUMP_COUNTER) {
						
						String msg = "[Informational]: \n" + pkt.toVerboseString();
						ConnectionImpl.connectionLogger.log(Level.WARNING, msg);

						if (dumpConnectionState) {
							conn.printDebugState();
						}
					}
				}
			}
		}

		if (isClosed) {
			return null;
		}

		return dequeue();
	}

    /**
     * bug 6189645 -- general blocking issues.
     * (conn.protocolHandler == null) is true when connection is closed.
     */
    private boolean shouldExit (ConnectionImpl conn) {

        if ( conn.connectionIsBroken || (conn.protocolHandler == null) ||
             conn.recoverInProcess ) {
            return true;
        } else {
            return false;
        }

    }

    protected void resend (ConnectionImpl conn, ReadWritePacket pkt) {
        try {

            boolean shouldResend = checkPacketType(conn, pkt);

            if ( shouldResend ) {
                conn.protocolHandler.resend(pkt);
            }

        } catch (Exception e) {
            Debug.printStackTrace(e);

            this.isClosed = true;
        }
    }

    /**
     * XXX HAWK: NOT all packets are resent to broker.
     * @param pkt ReadWritePacket
     * @return boolean
     */
    private boolean checkPacketType (ConnectionImpl conn,ReadWritePacket pkt) {

        //do not retry if not HA connection
        if ( conn.isConnectedToHABroker == false ) {
            return false;
        }

        boolean canResend = false;

        int ptype = pkt.getPacketType();

        switch (ptype) {
            case PacketType.ADD_CONSUMER:
            case PacketType.ADD_PRODUCER:
            case PacketType.CREATE_DESTINATION:
            case PacketType.CREATE_SESSION:
            case PacketType.GET_LICENSE:
            case PacketType.GOODBYE:
            case PacketType.HELLO:
            case PacketType.SET_CLIENTID:
            case PacketType.STOP:
            case PacketType.COMMIT_TRANSACTION:
            case PacketType.ROLLBACK_TRANSACTION:
            case PacketType.PREPARE_TRANSACTION:
            case PacketType.VERIFY_TRANSACTION:
                 canResend = true;
                 break;
            default:
                canResend = false;
                break;
        }

        return canResend;
    }

    protected void printInfo (ConnectionImpl conn, ReadWritePacket pkt, String duration) {

        /**
         * packet type
         */

         String type = PacketType.getString(pkt.getPacketType());

         /**
          * Get warning message.
          */
          String msg =
          AdministeredObject.cr.getKString(AdministeredObject.cr.W_WAITING_FOR_RESPONSE,type,duration);

          msg =
                msg + ", broker addr=" +
                conn.getProtocolHandler().getConnectionHandler().getBrokerAddress()
                + ", connectionID=" +
                conn.connectionID
                + ", clientID=" +
                conn.clientID
                + ", consumerID=" +
                pkt.getConsumerID();

          /**
           * dump to output stream.
           */
           //Debug.info(msg);
           
           ConnectionImpl.connectionLogger.log(Level.WARNING, msg);
    }

    public void dump (PrintStream ps) {
        ps.println ("------ AckQueue dump ------");
        ps.println("isEmpty: " + isEmpty());

        ps.println ("isClosed: " + isClosed);

        if ( size() > 0 ) {
            ps.println ("^^^^^^ ack queue super class dump ^^^^^^");
            super.dump(ps);
            ps.println ("^^^^^^ end ack queue super class dump ^^^^^^");
        }
    }

}