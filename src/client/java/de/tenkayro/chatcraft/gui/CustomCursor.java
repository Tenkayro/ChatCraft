package de.tenkayro.chatcraft.gui;

import de.tenkayro.chatcraft.ChatCraftClient;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public final class CustomCursor {
    private static final String TEXTURE_PATH =
            "/assets/chatcraft/textures/gui/cursor.png";
    private static final int HOTSPOT_X = 2;
    private static final int HOTSPOT_Y = 1;
    private static long handle;

    private CustomCursor() { }

    public static void install(MinecraftClient client) {
        try (InputStream stream = CustomCursor.class.getResourceAsStream(TEXTURE_PATH)) {
            if (stream == null) throw new IOException("Cursor texture is missing: " + TEXTURE_PATH);
            BufferedImage source = ImageIO.read(stream);
            if (source == null) throw new IOException("Cursor texture could not be decoded");

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer pixels = stack.malloc(source.getWidth() * source.getHeight() * 4);
                for (int y = 0; y < source.getHeight(); y++) {
                    for (int x = 0; x < source.getWidth(); x++) {
                        int argb = source.getRGB(x, y);
                        pixels.put((byte) (argb >> 16));
                        pixels.put((byte) (argb >> 8));
                        pixels.put((byte) argb);
                        pixels.put((byte) (argb >> 24));
                    }
                }
                pixels.flip();

                GLFWImage image = GLFWImage.malloc(stack)
                        .width(source.getWidth())
                        .height(source.getHeight())
                        .pixels(pixels);
                long newHandle = GLFW.glfwCreateCursor(image, HOTSPOT_X, HOTSPOT_Y);
                if (newHandle == 0L) throw new IllegalStateException("GLFW rejected the cursor image");
                destroy(client);
                handle = newHandle;
                GLFW.glfwSetCursor(client.getWindow().getHandle(), handle);
            }
        } catch (IOException | RuntimeException exception) {
            ChatCraftClient.LOGGER.error("Could not install the custom ChatCraft cursor", exception);
        }
    }

    public static void destroy(MinecraftClient client) {
        if (handle == 0L) return;
        GLFW.glfwSetCursor(client.getWindow().getHandle(), 0L);
        GLFW.glfwDestroyCursor(handle);
        handle = 0L;
    }
}
