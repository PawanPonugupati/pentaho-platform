/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/


package org.pentaho.platform.plugin.services.importexport;

import junit.framework.TestCase;
import org.apache.logging.log4j.Level;

import java.io.ByteArrayOutputStream;

/**
 * This test creates two mock import logs in two threads to test thread safety. Html output is sent to a
 * ByteArrayOutputStream rather than a FileOutput stream so as not to leave any residue.
 * 
 * @author TKafalas
 * 
 */
public class RepositoryImportLogTest extends TestCase {

  static IRepositoryImportLogger logger = new Log4JRepositoryImportLogger();

  public void testMultiThreadLog() {
    RepositoryImportLogTest.TestRun r1 = new RepositoryImportLogTest.TestRun( "bin/logOutput1.html", "1", logger );
    RepositoryImportLogTest.TestRun r2 = new RepositoryImportLogTest.TestRun( "bin/logOutput2.html", "2", logger );
    Thread t1 = new Thread( r1 );
    Thread t2 = new Thread( r2 );
    t1.start();
    t2.start();
    try {
      t1.join( 10000 );
      t2.join( 10000 );
    } catch ( InterruptedException e ) {
      fail( e.toString() );
    }
    if ( r1.getTestException() != null ) {
      fail( "Thread 1 Failed with " + r1.getTestException().getMessage() );
    }
    if ( r2.getTestException() != null ) {
      fail( "Thread 2 Failed with " + r2.getTestException().getMessage() );
    }
  }

  public static class TestRun implements Runnable {
    String outputFile;
    String threadNumber;
    IRepositoryImportLogger logger;
    Exception testException = null; // Cannot fail in another thread so have to pass it back to original thread

    TestRun( String outputFile, String threadNumber, IRepositoryImportLogger logger ) {
      this.outputFile = outputFile;
      this.threadNumber = threadNumber;
      this.logger = logger;
    }

    /**
     * @return the testException
     */
    public Exception getTestException() {
      return testException;
    }

    @Override
    public void run() {
      try {
        /**
         * Actual file creation disabled for junit testing.
         * 
         * To create actual html files, comment the next line and un-comment the one after it.
         */
        ByteArrayOutputStream fileStream = new ByteArrayOutputStream();
        // FileOutputStream fileStream = new FileOutputStream(outputFile);

        // You must call this method to start posting the log.
        logger.startJob( fileStream, mainDir(), Level.DEBUG );

        logger.setCurrentFilePath( mainDir() + "/dir2/file1" );
        logger.info( "Success" );

        logger.setCurrentFilePath( mainDir() + "/dir2/file2" );

        // Simulate an exception
        try {
          throw new RuntimeException( "forced exception" );
        } catch ( Exception e ) {
          logger.error( e );
        }
        // End of job
        logger.endJob();

        String logText = fileStream.toString();
        if ( logText.indexOf( "<td title=\"importFile\">" + mainDir() + "/dir2/file2</td>" ) < 0 ) {
          throw new RuntimeException( "Log " + threadNumber + " is not in correct format" );
        }
      } catch ( Exception e ) {
        testException = e;
      }
    }

    private String mainDir() {
      return "/dir" + threadNumber;
    }
  }
}
