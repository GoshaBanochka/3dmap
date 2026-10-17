package threed_map.client.map.three;

import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Camera orbiting a target point. Yaw 0 looks north, like the 2D map; pitch 90 looks straight down.
 * Matrices work in mesh-local coordinates (world minus the mesh origin) to keep floats precise.
 */
public final class OrbitCamera {
    private static final float FOV_DEGREES = 60;
    private static final float MIN_PITCH = 12;
    private static final float MAX_PITCH = 89;
    public static final float MIN_DISTANCE = 24;
    public static final float MAX_DISTANCE = 1500;

    private float yaw = 30;
    private float pitch = 50;
    private float distance = 180;

    public float yaw() {
        return yaw;
    }

    public float distance() {
        return distance;
    }

    public void rotate(double dYaw, double dPitch) {
        yaw = Mth.wrapDegrees((float) (yaw + dYaw));
        pitch = Mth.clamp((float) (pitch + dPitch), MIN_PITCH, MAX_PITCH);
    }

    public void zoom(double factor) {
        distance = Mth.clamp((float) (distance * factor), MIN_DISTANCE, MAX_DISTANCE);
    }

    public Matrix4f view(float targetX, float targetY, float targetZ) {
        return new Matrix4f()
                .translate(0, 0, -distance)
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw))
                .translate(-targetX, -targetY, -targetZ);
    }

    public Matrix4f projection(float aspect) {
        return new Matrix4f().perspective((float) Math.toRadians(FOV_DEGREES), aspect, 0.5f, distance * 4 + 2000);
    }

    /** World direction that moves "up" on screen when panning, on the ground plane: {x, z}. */
    public double[] forward() {
        double r = Math.toRadians(yaw);
        return new double[]{Math.sin(r), -Math.cos(r)};
    }

    /** World direction of screen right on the ground plane: {x, z}. */
    public double[] right() {
        double r = Math.toRadians(yaw);
        return new double[]{Math.cos(r), Math.sin(r)};
    }

    /**
     * Projects a mesh-local point to normalized screen coordinates {x 0..1, y 0..1 (top = 0)},
     * or null if it is behind the camera.
     */
    public static double[] project(Matrix4f viewProjection, float x, float y, float z) {
        Vector4f v = viewProjection.transform(new Vector4f(x, y, z, 1));
        if (v.w <= 0.01f) {
            return null;
        }
        return new double[]{(v.x / v.w + 1) / 2, (1 - v.y / v.w) / 2};
    }
}
