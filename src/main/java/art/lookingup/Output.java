package art.lookingup;

import art.lookingup.ui.UIPixliteConfig;
import heronarts.lx.LX;
import heronarts.lx.model.LXPoint;
import heronarts.lx.output.ArtNetDatagram;
import heronarts.lx.output.ArtSyncDatagram;
import heronarts.lx.output.LXDatagramOutput;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles output from our 'colors' buffer to our DMX lights.  Currently using E1.31.
 */
public class Output {
  private static final Logger logger = Logger.getLogger(Output.class.getName());

  public static LXDatagramOutput datagramOutput = null;

  public static final int MAX_OUTPUTS = 32;  // 32 outputs in expanded mode.
  public static final int RAVE_OUTPUTS = 8;
  public static final int RAVE_UNIVERSES_PER_OUTPUT = 2;
  public static final int RAVE_UNIVERSES = RAVE_OUTPUTS * RAVE_UNIVERSES_PER_OUTPUT;

  public static List<List<Integer>> outputs = new ArrayList<List<Integer>>(MAX_OUTPUTS);
    
  /**
   * Loads a wiring.txt file that is written by PixelMapping Processing sketch.
   *
   * @param filename
   * @return
   */
  static protected boolean loadWiring(String filename) {
    for (int i = 0; i < MAX_OUTPUTS; i++) {
      outputs.add(new ArrayList<Integer>());
    }
    BufferedReader reader;
    int currentOutputNum = 0;
    List<Integer> currentOutputIndices = null;

    try {
      reader = new BufferedReader(new FileReader(filename));
      String line = reader.readLine();
      while (line != null) {
        // logger.log(Level.INFO, "Reading wiring: " + line);
        if (line.startsWith(":")) {
          currentOutputNum = Integer.parseInt(line.replace(":", ""));
          currentOutputIndices = outputs.get(currentOutputNum);
        } else {
          int pointIndex = Integer.parseInt(line);
          currentOutputIndices.add(pointIndex);
        }
        line = reader.readLine();
      }
      reader.close();
    } catch (IOException e) {
      e.printStackTrace();
      return false;
    }
    return true;
  }

  public static String artnetIpAddress = "192.168.2.120";
  public static int artnetPort = 6454;

  // TODO(tracy): We need to put out the points in the same order for the CNC-based panels that we did for
  // the dimensions-based generated panels.
  public static void configureUnityArtNet(LX lx) {
    List<LXPoint> points = lx.getModel().getPoints();
    int numUniverses = (int)Math.ceil(((double)points.size())/170.0);
    System.out.println("Num universes: " + numUniverses);
    List<ArtNetDatagram> datagrams = new ArrayList<ArtNetDatagram>();
    int totalPointsOutput = 0;

    // Output by panel.

    int curUnivNum = 0;
    int curDmxAddress = 0;


    /*
    int[] dmxChannelsForUniverse = new int[170];
    for (Panel panel : ConeDownModel.allPanels) {
      int numPanelPoints = panel.getPoints().size();
     System.out.println("Adding points for panelType: " + panel.pointsWide + "," + panel.pointsHigh);
     if (panel.panelRegion == Panel.PanelRegion.CONE) {
       for (int col = 0; col < panel.pointsWide; col++) {
        for (int row = 0; row < panel.pointsHigh; row++) {
           CXPoint p = CXPoint.getCXPointAtTexCoord(panel.getPoints(), col, row);
           dmxChannelsForUniverse[curDmxAddress++] = p.index;
           if (curDmxAddress >= 170) {
             System.out.println("Added points for universe number: " + curUnivNum);
             ArtNetDatagram artnetDatagram = new ArtNetDatagram(dmxChannelsForUniverse, curUnivNum);
             try {
               artnetDatagram.setAddress(artnetIpAddress).setPort(artnetPort);
             } catch (UnknownHostException uhex) {
               logger.log(Level.SEVERE, "Configuring ArtNet: " + artnetIpAddress, uhex);
             }
             datagrams.add(artnetDatagram);
             curUnivNum++;
             curDmxAddress = 0;
             dmxChannelsForUniverse = new int[170];
           }
         }
       }
     } else {
       for (int row = 0; row < panel.pointsHigh; row++) {
         for (int col = 0; col < panel.pointsWide; col++) {
           CXPoint p = CXPoint.getCXPointAtTexCoord(panel.getPoints(), col, row);
           dmxChannelsForUniverse[curDmxAddress++] = p.index;
           if (curDmxAddress >= 170) {
             System.out.println("Added points for universe number: " + curUnivNum);
             ArtNetDatagram artnetDatagram = new ArtNetDatagram(dmxChannelsForUniverse, curUnivNum);
             try {
               artnetDatagram.setAddress(artnetIpAddress).setPort(artnetPort);
             } catch (UnknownHostException uhex) {
               logger.log(Level.SEVERE, "Configuring ArtNet: " + artnetIpAddress, uhex);
             }
             datagrams.add(artnetDatagram);
             curUnivNum++;
             curDmxAddress = 0;
             dmxChannelsForUniverse = new int[170];
           }
         }
       }
     }
    }
    */

    for (int univNum = 0; univNum < numUniverses; univNum++) {
      int[] dmxChannelsForUniverse = new int[170];
      for (int i = 0; i < 170 && totalPointsOutput < points.size(); i++) {
        LXPoint p = points.get(univNum*170 + i);
        dmxChannelsForUniverse[i] = p.index;
        totalPointsOutput++;
      }
      System.out.println("Added points for universe number: " + univNum);
      ArtNetDatagram artnetDatagram = new ArtNetDatagram(dmxChannelsForUniverse, univNum);
      try {
        artnetDatagram.setAddress(artnetIpAddress).setPort(artnetPort);
      } catch (UnknownHostException uhex) {
        logger.log(Level.SEVERE, "Configuring ArtNet: " + artnetIpAddress, uhex);
      }
      datagrams.add(artnetDatagram);
    }


    for (ArtNetDatagram dgram : datagrams) {
      try {
        datagramOutput = new LXDatagramOutput(lx);
        datagramOutput.addDatagram(dgram);
      } catch (SocketException sex) {
        logger.log(Level.SEVERE, "Initializing LXDatagramOutput failed.", sex);
      }
      if (datagramOutput != null) {
        lx.engine.output.addChild(datagramOutput);
      } else {
        logger.log(Level.SEVERE, "Did not configure output, error during LXDatagramOutput init");
      }
    }
  }

  public static void configureArtNetOutput(LX lx) {
    loadWiring("wiring.txt");
    // This only works if we have less than 170 lxpoints.
    String artNetIpAddress = ConeDown.pixliteConfig.getStringParameter(UIPixliteConfig.PIXLITE_1_IP).getString();
    int artNetIpPort = Integer.parseInt(ConeDown.pixliteConfig.getStringParameter(UIPixliteConfig.PIXLITE_1_PORT).getString());
    logger.log(Level.INFO, "Using ArtNet: " + artNetIpAddress + ":" + artNetIpPort);

    List<ArtNetDatagram> datagrams = new ArrayList<ArtNetDatagram>();

    int outputNumber = 1;
    int universeNumber = 0;

    while (universeNumber < RAVE_UNIVERSES) {
      for (List<Integer> indices : outputs) {
        // For the Rave sign, we only have outputs 1 through 4 mapped.  If there is nothing on the output in the
        // wiring.txt file skip it.  We will make 2 passes of the wiring.txt file, one for each side of the sign.
        if (indices.size() == 0) continue;
        // Add point indices in chunks of 170.  After 170 build datagram and then increment the universeNumber.
        // Continuing adding points and building datagrams every 170 points.  After all points for an output
        // have been added to datagrams, start on a new output and reset counters.
        int chunkNumber = 0;
        int pointNum = 0;
        while (pointNum + chunkNumber * 170 < indices.size()) {
          // Compute the dataLength.  For a string of 200 leds, we should have dataLengths of
          // 170 and then 30.  So for the second pass, chunkNumber=1.  Overrun is 2*170 - 200 = 340 - 200 = 140
          // We subtract 170-overrun = 30, which is the remainder number of the leds on the last chunk.
          // 350 leds = chunkNumber = 2, 510 - 350 = 160.  170-160=10.
          int overrun = ((chunkNumber + 1) * 170) - indices.size();
          int dataLength = (overrun < 0) ? 170 : 170 - overrun;
          int[] thisUniverseIndices = new int[dataLength];
          // For each chunk of 170 points, add them to a datagram.
          for (pointNum = 0; pointNum < 170 && (pointNum + chunkNumber * 170 < indices.size());
               pointNum++) {
            int pIndex = indices.get(pointNum + chunkNumber * 170);
            if (outputNumber > RAVE_OUTPUTS/2) pIndex += 1050;
            thisUniverseIndices[pointNum] = pIndex;
           }
          System.out.println("thisUniverseIndices.length: " + thisUniverseIndices.length);
          for (int k = 0; k < thisUniverseIndices.length; k++) {
            System.out.print("" + thisUniverseIndices[k] + ",");
          }
          System.out.println("");
          logger.log(Level.INFO, "Adding datagram: output=" + outputNumber + " universe=" + universeNumber + " points=" + pointNum);
          ArtNetDatagram artNetDatagram = new ArtNetDatagram(thisUniverseIndices, dataLength*3, universeNumber);
          try {
            artNetDatagram.setAddress(artNetIpAddress).setPort(artNetIpPort);
          } catch (UnknownHostException uhex) {
            logger.log(Level.SEVERE, "Configuring ArtNet: " + artNetIpAddress + ":" + artNetIpPort, uhex);
          }
          datagrams.add(artNetDatagram);
          // We have either added 170 points and maybe less if it is the last few points for a given output.  Each
          // time we build a datagram for a chunk, we need to increment the universeNumber, reset the pointNum to zero,
          // and increment our chunkNumber
          ++universeNumber;
          pointNum = 0;
          chunkNumber++;
        }
        outputNumber++;
      }
    }
    try {
      datagramOutput = new LXDatagramOutput(lx);
      for (ArtNetDatagram datagram : datagrams) {
        datagramOutput.addDatagram(datagram);
      }
      try {
        datagramOutput.addDatagram(new ArtSyncDatagram().setAddress(artNetIpAddress).setPort(artNetIpPort));
      } catch (UnknownHostException uhex) {
        logger.log(Level.SEVERE, "Unknown host for ArtNet sync.", uhex);
      }
    } catch (SocketException sex) {
      logger.log(Level.SEVERE, "Initializing LXDatagramOutput failed.", sex);
    }
    if (datagramOutput != null) {
      lx.engine.output.addChild(datagramOutput);
    } else {
      logger.log(Level.SEVERE, "Did not configure output, error during LXDatagramOutput init");
    }
  }
}
