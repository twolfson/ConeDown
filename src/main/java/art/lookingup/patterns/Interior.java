package art.lookingup.patterns;

import heronarts.lx.LX;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import art.lookingup.ConeDownModel;
import art.lookingup.colors.Colors;
import heronarts.lx.LX;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.CompoundParameter;

public class Interior extends RPattern {

  public final CompoundParameter fpsKnob =
      new CompoundParameter("Fps", 61.0, 0.0, 61.0)
          .setDescription("Controls the frames per second.");
  public final CompoundParameter tailKnob =
      new CompoundParameter("Tail", 85.0, 0.0, 1051.0)
          .setDescription("Length of tail");

  protected int pos = 0;
  protected int tailLength = 3;
  protected double currentFrame = 0.0;
  protected int previousFrame = -1;
  protected double deltaDrawMs = 0.0;

  public Interior(LX lx) {
    super(lx);
    addParameter(fpsKnob);
    addParameter(tailKnob);
  }

  public void render(double deltaMs) {
    double fps = fpsKnob.getValue();
    currentFrame += (deltaMs / 1000.0) * fps;
    // We don't call draw() every frame so track the accumulated deltaMs for them.
    deltaDrawMs += deltaMs;
    if ((int) currentFrame > previousFrame) {
      // Time for new frame.
      renderInterior(deltaDrawMs);
      previousFrame = (int) currentFrame;
      deltaDrawMs = 0.0;
    }
    // Don't let current frame increment forever.  Otherwise float will
    // begin to lose precision and things get wonky.
    if (currentFrame > 10000.0) {
      currentFrame = 0.0;
      previousFrame = -1;
    }
  }

  public void renderInterior(double deltaMs) {
    for (LXPoint p  : ConeDownModel.interiorPoints) {
      colors[p.index] = Colors.RED;
    }
  }
}
