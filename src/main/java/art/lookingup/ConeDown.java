package art.lookingup;

import art.lookingup.ui.*;
import com.google.common.reflect.ClassPath;
import heronarts.lx.LXEffect;
import heronarts.lx.LXPattern;
import heronarts.lx.model.LXModel;
import heronarts.lx.studio.LXStudio;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import processing.core.PApplet;

public class ConeDown extends PApplet {
	
	static {
    System.setProperty(
        "java.util.logging.SimpleFormatter.format",
        "%3$s: %1$tc [%4$s] %5$s%6$s%n");
  }

    /**
   * Set the main logging level here.
   *
   * @param level the new logging level
   */
  public static void setLogLevel(Level level) {
    // Change the logging level here
    Logger root = Logger.getLogger("");
    root.setLevel(level);
    for (Handler h : root.getHandlers()) {
      h.setLevel(level);
    }
  }


  /**
   * Adds logging to a file. The file name will be appended with a dash, date stamp, and
   * the extension ".log".
   *
   * @param prefix prefix of the log file name
   * @throws IOException if there was an error opening the file.
   */
  public static void addLogFileHandler(String prefix) throws IOException {
    String suffix = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    Logger root = Logger.getLogger("");
    Handler h = new FileHandler(prefix + "-" + suffix + ".log");
    h.setFormatter(new SimpleFormatter());
    root.addHandler(h);
  }

  private static final Logger logger = Logger.getLogger(ConeDown.class.getName());

  public static void main(String[] args) {
    PApplet.main(ConeDown.class.getName(), args);
  }

  private static final String LOG_FILENAME_PREFIX = "lookinguparts";

  // Reference to top-level LX instance
  private heronarts.lx.studio.LXStudio lx;

  public static PApplet pApplet;
  public static final int GLOBAL_FRAME_RATE = 40;

  public static RainbowOSC rainbowOSC;

  public static UIGammaSelector gammaControls;
  public static UIModeSelector modeSelector;
  public static UIAudioMonitorLevels audioMonitorLevels;
  public static UIPixliteConfig pixliteConfig;
  public static UIMidiControl uiMidiControl;
  public static com.giantrainbow.OSCSensor oscSensor;
  public static OSCSensorUI oscSensorUI;
  public static UIFirmata firmataPortUI;
  public static UIGalacticJungle galacticJungle;

  // The standard projections provide anti-aliasing at levels from
  // some (2) to plenty (4).
  public static int MIN_SUPER_SAMPLING = 1;
  public static int DEFAULT_SUPER_SAMPLING = 2;
  public static int MAX_SUPER_SAMPLING = 4;

  private static Projection projections[];

  @Override
  public void settings() {
    size(1400, 678, P3D);
  }

  public static Projection getProjection(int ss) {
      ss = Math.min(ss, MAX_SUPER_SAMPLING);
      ss = Math.max(ss, MIN_SUPER_SAMPLING);
      return projections[ss];
  }

  /**
   * Registers all patterns and effects that LX doesn't already have registered.
   * This check is important because LX just adds to a list.
   *
   * @param lx the LX environment
   */
  private void registerAll(LXStudio lx) {
    List<Class<? extends LXPattern>> patterns = lx.getRegisteredPatterns();
    List<Class<? extends LXEffect>> effects = lx.getRegisteredEffects();
    final String parentPackage = getClass().getPackage().getName();

    try {
      ClassPath classPath = ClassPath.from(getClass().getClassLoader());
      for (ClassPath.ClassInfo classInfo : classPath.getAllClasses()) {
        // Limit to this package and sub-packages
        if (!classInfo.getPackageName().startsWith(parentPackage)) {
          continue;
        }
        Class<?> c = classInfo.load();
        if (Modifier.isAbstract(c.getModifiers())) {
          continue;
        }
        if (LXPattern.class.isAssignableFrom(c)) {
          Class<? extends LXPattern> p = c.asSubclass(LXPattern.class);
          if (!patterns.contains(p)) {
            lx.registerPattern(p);
            logger.info("Added pattern: " + p);
          }
        } else if (LXEffect.class.isAssignableFrom(c)) {
          Class<? extends LXEffect> e = c.asSubclass(LXEffect.class);
          if (!effects.contains(e)) {
            lx.registerEffect(e);
            logger.info("Added effect: " + e);
          }
        }
      }
    } catch (IOException ex) {
      logger.log(Level.WARNING, "Error finding pattern and effect classes", ex);
    }
  }

  static String readFile(String path, Charset encoding)
      throws IOException
  {
    byte[] encoded = Files.readAllBytes(Paths.get(path));
    return new String(encoded, encoding);
  }

  @Override
  public void setup() {
    // Processing setup, constructs the window and the LX instance
    pApplet = this;

    try {
      addLogFileHandler(LOG_FILENAME_PREFIX);
    } catch (IOException ex) {
      logger.log(Level.SEVERE, "Error creating log file: " + LOG_FILENAME_PREFIX, ex);
    }

    LXModel model = ConeDownModel.createModel();

    logger.info("Computing true projection");
    projections = new Projection[MAX_SUPER_SAMPLING + 1];
    projections[1] = new TrueProjection(model);
    for (int i = 2; i <= MAX_SUPER_SAMPLING; i++) {
	logger.info("Computing " + i + "x projection");
	projections[i] = new AntiAliased(model, i);
    }
    logger.info("Computed all projections");

    LXStudio.Flags flags = new LXStudio.Flags();
    //flags.showFramerate = false;
    //flags.isP3LX = true;
    //flags.immutableModel = true;
    flags.useGLPointCloud = false;
    flags.startMultiThreaded = false;
    //flags.showFramerate = true;

    logger.info("Current renderer:" + sketchRenderer());
    logger.info("Current graphics:" + getGraphics());
    logger.info("Current graphics is GL:" + getGraphics().isGL());
    //logger.info("Multithreaded hint: " + MULTITHREADED);
    //logger.info("Multithreaded actually: " + (MULTITHREADED && !getGraphics().isGL()));
    lx = new LXStudio(this, flags, model);

    lx.ui.setResizable(true);

    // Put this here because it needs to be after file loads in order to find appropriate channels.
    modeSelector = (UIModeSelector) new UIModeSelector(lx.ui, lx, audioMonitorLevels).setExpanded(true).addToContainer(lx.ui.leftPane.global);
    modeSelector.standardMode.setActive(true);
    //frameRate(GLOBAL_FRAME_RATE);
  }


  public void initialize(final LXStudio lx, LXStudio.UI ui) {
    // Add custom components or output drivers here
    // Register settings
    // lx.engine.registerComponent("yomigaeSettings", new Settings(lx, ui));

    // Common components
    // registry = new Registry(this, lx);

    // Register any patterns and effects LX doesn't recognize
    registerAll(lx);
  }

  public void onUIReady(LXStudio lx, LXStudio.UI ui) {
    firmataPortUI = (UIFirmata) new UIFirmata(lx.ui, lx).setExpanded(true).addToContainer(lx.ui.leftPane.global);
    ConeFirmata.reloadFirmata(firmataPortUI.getStringParameter(UIFirmata.FIRMATA_PORT).getString(), firmataPortUI.numTiles,
        firmataPortUI.getDiscreteParameter(UIFirmata.START_PIN).getValuei(), firmataPortUI.getPinParameters());
    oscSensor = new com.giantrainbow.OSCSensor(lx);
    lx.engine.registerComponent("oscsensor", oscSensor);
    //modeSelector = (UIModeSelector) new UIModeSelector(lx.ui, lx, audioMonitorLevels).setExpanded(true).addToContainer(lx.ui.leftPane.global);
    //modeSelector = (UIModeSelector) new UIModeSelector(lx.ui, lx, audioMonitorLevels).setExpanded(true).addToContainer(lx.ui.leftPane.global);
    oscSensorUI = (OSCSensorUI) new OSCSensorUI(lx.ui, lx, oscSensor).setExpanded(false).addToContainer(lx.ui.leftPane.global);

    audioMonitorLevels = (UIAudioMonitorLevels) new UIAudioMonitorLevels(lx.ui).setExpanded(false).addToContainer(lx.ui.leftPane.global);
    gammaControls = (UIGammaSelector) new UIGammaSelector(lx.ui).setExpanded(false).addToContainer(lx.ui.leftPane.global);
    uiMidiControl = (UIMidiControl) new UIMidiControl(lx.ui, lx, modeSelector).setExpanded(false).addToContainer(lx.ui.leftPane.global);
    pixliteConfig = (UIPixliteConfig) new UIPixliteConfig(lx.ui, lx).setExpanded(false).addToContainer(lx.ui.leftPane.global);
    galacticJungle = (UIGalacticJungle) new UIGalacticJungle(lx.ui, lx).setExpanded(false).addToContainer(lx.ui.leftPane.global);

    lx.engine.midi.addListener(uiMidiControl);
    if (enableOutput) {
      Output.configurePixliteOutput(lx);
      //Output.configureUnityArtNet(lx);
      // By default the output in Galactic Jungle is disabled.
      Output.outputGalacticJungle(lx);

    }
    if (disableOutputOnStart)
      lx.engine.output.enabled.setValue(false);

    rainbowOSC = new RainbowOSC(lx);

    // Disable preview for faster UI.
    //lx.ui.preview.setVisible(false);
  }

  public void draw() {
    // All is handled by LX Studio
  }

  // Configuration flags
  private final static boolean MULTITHREADED = false;  // Disabled for anything GL
                                                       // Enable at your own risk!
                                                       // Could cause VM crashes.
  private final static boolean RESIZABLE = true;

  // Helpful global constants
  final static float INCHES = 1.0f / 12.0f;
  final static float IN = INCHES;
  final static float FEET = 1.0f;
  final static float FT = FEET;
  final static float CM = IN / 2.54f;
  final static float MM = CM * .1f;
  final static float M = CM * 100;
  final static float METER = M;

  public static final boolean enableOutput = true;
  public static final boolean disableOutputOnStart = true;

  public static final int LEDS_PER_UNIVERSE = 170;
}
