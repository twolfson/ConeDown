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
import java.io.PrintWriter;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static art.lookingup.ConeDownModel.panelLayers;

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
    logger.info("Num universes: " + numUniverses);
    List<ArtNetDatagram> datagrams = new ArrayList<ArtNetDatagram>();
    int totalPointsOutput = 0;

    for (int univNum = 0; univNum < numUniverses; univNum++) {
      int[] dmxChannelsForUniverse = new int[170];
      for (int i = 0; i < 170 && totalPointsOutput < points.size(); i++) {
        LXPoint p = points.get(univNum*170 + i);
        dmxChannelsForUniverse[i] = p.index;
        totalPointsOutput++;
      }
      logger.info("Added points for universe number: " + univNum);
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

  /**
   * Each Pixlite output covers one sixteenth of the installation.  Dance floor is another 1 or 2 outputs.
   * Probably 2.
   * @param lx
   */
  public static void configurePixliteOutput(LX lx) {
    List<ArtNetDatagram> datagrams = new ArrayList<ArtNetDatagram>();
    List<Integer> countsPerOutput = new ArrayList<Integer>();
    // For each output, track the number of points per panel type so we can log the details to help
    // with output verification.
    List<Map<String, Integer>> countsByPanelType = new ArrayList<Map<String, Integer>>();
    List<Map<String, String>> allDXFByPanelType = new ArrayList<Map<String, String>>();


    String artNetIpAddress = ConeDown.pixliteConfig.getStringParameter(UIPixliteConfig.PIXLITE_1_IP).getString();
    int artNetIpPort = Integer.parseInt(ConeDown.pixliteConfig.getStringParameter(UIPixliteConfig.PIXLITE_1_PORT).getString());
    logger.log(Level.INFO, "Using ArtNet: " + artNetIpAddress + ":" + artNetIpPort);

    int sixteenthNum = 0;
    // NOTE(tracy): universesPerSixteenth needs to be set correctly.  Some outputs use less than 3 universes but
    // we will just set 3 here and waste a few universes.
    int universesPerSixteenth = 3;
    Set wireFilesWritten = new HashSet();
    for (sixteenthNum = 0; sixteenthNum < 16; sixteenthNum++) {
      // Some utility datastructures for reporting the results of the output mapping later.  This is just
      // mean to help verify the output mapping.
      Map<String, Integer> pointCountByPanelType = new HashMap<String, Integer>();
      countsByPanelType.add(pointCountByPanelType);
      Map<String, String> dxfByPanelType = new HashMap<String, String>();
      allDXFByPanelType.add(dxfByPanelType);
      // This is a list of panel keys used for wiring.  We want them in wire order so that we can generate
      // some HTML documentation for each sixteenth.
      List<String> panelKeysInWireOrder = new ArrayList<String>();

      int univStartNum = sixteenthNum * universesPerSixteenth;
      // First we will collect all our points in wire order.  These points will span multiple
      // panels and multiple universes.  Once we have all the points for a given sixteenth wire
      // then we will start packing them into ArtNetDatagrams with 170 points per universe.
      List<CXPoint> allPointsWireOrder = new ArrayList<CXPoint>();
      for (List<Panel> layer : panelLayers) {
        // NOTE(Tracy):
        Panel panel = layer.get(0);
        logger.info("");
        logger.info("panel layer: " + Panel.panelTypeNames[panel.panelType.ordinal()]);
        logger.info("dim: " + panel.pointsWide + "x" + panel.pointsHigh);
        logger.info("sixteenth: " + sixteenthNum);

        // For the I panel layer, we don't have any panels between 5 and 10.
        if (panel.panelType == Panel.PanelType.I && (sixteenthNum > 4 && sixteenthNum < 11)) {
          logger.info("Skipping output for nonexistent I panel # " + sixteenthNum);
          continue;
        }

        // C and D panels each span an entire octant (2 sixteenths).  To minimize
        // leds per output we alternate C and D on different outputs.
        if (panel.panelType == Panel.PanelType.C && sixteenthNum % 2 == 0) {
          panel = layer.get(sixteenthNum / 2);
        } else if (panel.panelType == Panel.PanelType.D && sixteenthNum % 2 == 1) {
          logger.info("Assign panel to D panel");
          panel = layer.get(sixteenthNum / 2);
        } else if (!(panel.panelType == Panel.PanelType.C || panel.panelType == Panel.PanelType.D)) {
          int iGapOffset = 0;
          // Account for the missing I panels.
          if (panel.panelType == Panel.PanelType.I && sixteenthNum > 10)
            iGapOffset = 6;
          panel = layer.get(sixteenthNum - iGapOffset);
        } else {
          continue; // Skip C or D if it is not their turn.
        }
        logger.info("panelType: " + Panel.panelTypeNames[panel.panelType.ordinal()]);
        List<CXPoint> pointsWireOrder = panel.pointsInWireOrder();

        pointCountByPanelType.put(Panel.panelTypeNames[panel.panelType.ordinal()], pointsWireOrder.size());
        dxfByPanelType.put(Panel.panelTypeNames[panel.panelType.ordinal()], panel.dxfFilename);

        // Write points and wiring file.  These can be used with Pixel Mapper sketch to visually inspect
        // the result of the mapping code.
        // Only write once per panelType.
        String panelKey = Panel.panelTypeNames[panel.panelType.ordinal()] + "_" + panel.panelNum;
        String dxfbase = panel.dxfFilename.replace(".dxf", "").replace("panel_", "").replace("_LED", "");
        panelKey = dxfbase + "_" + panel.panelNum;
        if (!wireFilesWritten.contains(panelKey)) {
          String pointsFilename = "points_panel_" + dxfbase + "_" + panel.panelNum + ".csv";
          String wiringFilename = "wiring_panel_" + dxfbase + "_" + panel.panelNum + ".txt";
          writePointsFile(pointsFilename, pointsWireOrder);
          writeWiringFile(wiringFilename, pointsWireOrder);
          wireFilesWritten.add(panelKey);
          panelKeysInWireOrder.add(panelKey);
        }

        // pointsWireOrder contains our points in wiring order for this panel.
        allPointsWireOrder.addAll(pointsWireOrder);
      }

      // Write out HTML documentation for wiring each sixteenth
      writeSixteenthHtmlDoc(sixteenthNum, panelKeysInWireOrder);

      countsPerOutput.add(allPointsWireOrder.size());

      // NOTE(tracy): We have to create ArtNetDatagram with the actual numbers of our points or else it
      // will puke internally. i.e. we can't just use 170 but then pass it less than 170 points so we
      // need to figure out how large to make our channel array for the last universe.
      int numUniversesThisWire = (int)Math.ceil((float)allPointsWireOrder.size()/170f);
      int lastUniverseCount = allPointsWireOrder.size() - 170 * (numUniversesThisWire - 1);


      int[] thisUniverseIndices = new int[170];
      int curIndex = 0;
      int curUnivOffset = 0;
      for (CXPoint pt : allPointsWireOrder) {
        thisUniverseIndices[curIndex] = pt.index;
        curIndex++;
        if (curIndex == 170 || (curUnivOffset == numUniversesThisWire - 1 && curIndex == lastUniverseCount)) {
          logger.log(Level.INFO, "Adding datagram: universe=" + (univStartNum+curUnivOffset) + " points=" + curIndex);
          ArtNetDatagram datagram = new ArtNetDatagram(thisUniverseIndices, curIndex*3, univStartNum + curUnivOffset);
          try {
            datagram.setAddress(artNetIpAddress).setPort(artNetIpPort);
          } catch (UnknownHostException uhex) {
            logger.log(Level.SEVERE, "Configuring ArtNet: " + artNetIpAddress + ":" + artNetIpPort, uhex);
          }
          datagrams.add(datagram);
          curUnivOffset++;
          curIndex = 0;
          if (curUnivOffset == numUniversesThisWire - 1) {
            thisUniverseIndices = new int[lastUniverseCount];
          } else {
            thisUniverseIndices = new int[170];
          }
        }
      }
    }

    // Dance panels.  Requires 2 outputs to minimize strand length.  The first output starts with
    // dance tile 2,0 and then 1,0 and then 0,0 and then moves over and then up so dance tile
    // 0,1 and then 0,2 and then 0,3.
    // The second output is dance tile 2,2 then 2,1, then 2,0.
    List<CXPoint> pointsForDanceOutput1 = new ArrayList<CXPoint>();
    boolean down = true;
    for (int x = 0; x < ConeDownModel.dancePanelsWide - 1; x++) {
      for (int y = ConeDownModel.dancePanelsHigh-1; y >= 0; y--) {
        int actualY = (down)?y:(ConeDownModel.dancePanelsHigh - 1 - y);
        Panel panel = Panel.getDancePanelXY(ConeDownModel.dancePanels, x, actualY);
        List<CXPoint> pointsWireOrder = panel.pointsInWireOrder();
        logger.info("dance panel " + panel.danceXPanel + "," + panel.danceYPanel + " points: " +
            pointsWireOrder.size());
        pointsForDanceOutput1.addAll(pointsWireOrder);
      }
      down = false;
    }
    // Cone+Scoop uses 3 universes per sixteenth so we start at universe 48.
    List<ArtNetDatagram> danceOutput1Datagrams = assignPointsToArtNetDatagrams(pointsForDanceOutput1, 48,
        artNetIpAddress, artNetIpPort);
    countsPerOutput.add(pointsForDanceOutput1.size());
    datagrams.addAll(danceOutput1Datagrams);

    // Dance Output 2
    List<CXPoint> pointsForDanceOutput2 = new ArrayList<CXPoint>();
    down = true;
    int x = 2;
    for (int y = ConeDownModel.dancePanelsHigh-1; y >= 0; y--) {
      int actualY = (down)?y:(ConeDownModel.dancePanelsHigh - 1 - y);
      Panel panel = Panel.getDancePanelXY(ConeDownModel.dancePanels, x, actualY);
      List<CXPoint> pointsWireOrder = panel.pointsInWireOrder();
      pointsForDanceOutput2.addAll(pointsWireOrder);
      logger.info("dance panel " + panel.danceXPanel + "," + panel.danceYPanel + " points: " +
          pointsWireOrder.size());
    }

    // Dance output 1 used 2 universes (49 points per panel * 6 panels = 294 points @ 170-per-universe)
    List<ArtNetDatagram> danceOutput2Datagrams = assignPointsToArtNetDatagrams(pointsForDanceOutput2, 50,
        artNetIpAddress, artNetIpPort);
    countsPerOutput.add(pointsForDanceOutput2.size());
    datagrams.addAll(danceOutput2Datagrams);

    // Interior lights.  Dance output 2 used one universe so our start universe is 51.
    List<ArtNetDatagram> interiorDatagrams = assignPointsToArtNetDatagrams(ConeDownModel.interiorPoints,
        51, artNetIpAddress, artNetIpPort);
    countsPerOutput.add(ConeDownModel.interiorPoints.size());
    datagrams.addAll(interiorDatagrams);

    int i = 0;
    for (Integer count : countsPerOutput) {
      logger.info("output " + i + ": " + count + " points");
      // Only cone/scoop outputs have counts by panel type.
      if (i < 16) {
        Map<String, Integer> pointCountByPanelType = countsByPanelType.get(i);
        Map<String, String> dxfByPanelType = allDXFByPanelType.get(i);
        ArrayList<String> sortedKeys =
            new ArrayList<String>(pointCountByPanelType.keySet());
        Collections.sort(sortedKeys);
        for (String key : sortedKeys) {
          logger.info("   key= " + key + " count= " + pointCountByPanelType.get(key) + " dxf= " +
              dxfByPanelType.get(key));
        }
      }
      i++;
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
    logger.info("layers: " + panelLayers.size());
  }

  /**
   * Given a set of points and a starting universe, assign the points to a series of ArtNetDatagrams.  This encapsulates
   * the logic of chunking 170 pixels per universes.  Callers can determine the number of universes used by
   * checking the length of the returned list.
   * @param pointsWireOrder The points in wire order to map to ArtNet.
   * @param startUniverse The starting universe for the set of points.
   * @param ipAddress The IP Address for the ArtNet destination.
   * @param ipPort The Port number for the ArtNet destination.
   * @return
   */
  static public List<ArtNetDatagram> assignPointsToArtNetDatagrams(List<CXPoint> pointsWireOrder, int startUniverse,
                                                                   String ipAddress, int ipPort) {
    List<ArtNetDatagram> datagrams = new ArrayList<ArtNetDatagram>();

    // NOTE(tracy): We have to create ArtNetDatagram with the actual numbers of our points or else it
    // will puke internally. i.e. we can't just use 170 but then pass it less than 170 points so we
    // need to figure out how large to make our channel array for the last universe.
    int numUniversesThisWire = (int)Math.ceil((float)pointsWireOrder.size()/170f);
    int lastUniverseCount = pointsWireOrder.size() - 170 * (numUniversesThisWire - 1);
    int firstUniverseCount = (numUniversesThisWire>1)?170:lastUniverseCount;

    int[] thisUniverseIndices = new int[firstUniverseCount];
    int curIndex = 0;
    int curUnivOffset = 0;
    for (CXPoint pt : pointsWireOrder) {
      thisUniverseIndices[curIndex] = pt.index;
      curIndex++;
      if (curIndex == 170 || (curUnivOffset == numUniversesThisWire - 1 && curIndex == lastUniverseCount)) {
        logger.log(Level.INFO, "Adding datagram: universe=" + (startUniverse+curUnivOffset) + " points=" + curIndex);
        ArtNetDatagram datagram = new ArtNetDatagram(thisUniverseIndices, curIndex*3, startUniverse + curUnivOffset);
        try {
          datagram.setAddress(ipAddress).setPort(ipPort);
        } catch (UnknownHostException uhex) {
          logger.log(Level.SEVERE, "Configuring ArtNet: " +ipAddress + ":" + ipPort, uhex);
        }
        datagrams.add(datagram);
        curUnivOffset++;
        curIndex = 0;
        if (curUnivOffset == numUniversesThisWire - 1) {
          thisUniverseIndices = new int[lastUniverseCount];
        } else {
          thisUniverseIndices = new int[170];
        }
      }
    }
    return datagrams;
  }

  static public void writeSixteenthHtmlDoc(int sixteenth, List<String> panelKeysWireOrder) {
    String filename = "sixteenth_" + sixteenth + ".html";
    try {
      PrintWriter htmlFile = new PrintWriter(filename);
      htmlFile.println("<html><head><title>16th " + sixteenth + "</title></head><body><h1>16th " + sixteenth + "<h1>");
      for (int keyNum = panelKeysWireOrder.size() - 1; keyNum >= 0; --keyNum) {
        String panelKey = panelKeysWireOrder.get(keyNum);
        htmlFile.println("<img src=\"wiring/" + panelKey + ".png\"><br/>");
      }
      htmlFile.println("</body></html>");
      htmlFile.close();
    } catch (IOException ioex) {
      logger.info("IOException writing " + filename + ": " + ioex.getMessage());
    }
  }

  static public void writePointsFile(String filename, List<CXPoint> points) {
    try {
      PrintWriter lxpointsFile = new PrintWriter(filename);
      for (CXPoint p : points) {
        lxpointsFile.println(p.panelLocalX + "," + p.panelLocalY);
      }
      lxpointsFile.close();
    } catch (IOException ioex) {
      logger.info("IOException writing " + filename + ": " + ioex.getMessage());
    }
  }

  static public void writeWiringFile(String filename, List<CXPoint> points) {
    try {
      PrintWriter wiringFile = new PrintWriter(filename);
      int pNum = 0;
      wiringFile.println(":0");
      for (LXPoint p : points) {
        wiringFile.println(pNum);
        pNum++;
      }
      wiringFile.close();
    } catch (IOException ioex) {
      logger.info("IOException writing " + filename + ": " + ioex.getMessage());
    }
  }

  public static void configureUnityArtNetOutput(LX lx) {
    //loadWiring("wiring.txt");
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
          logger.info("thisUniverseIndices.length: " + thisUniverseIndices.length);
          for (int k = 0; k < thisUniverseIndices.length; k++) {
            logger.info("" + thisUniverseIndices[k] + ",");
          }
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
