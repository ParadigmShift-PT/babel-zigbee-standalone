package zigbee;

import com.zsmartsystems.zigbee.serialization.DefaultSerializer;
import java.lang.reflect.Field;

public class LargeFrameSerializer extends DefaultSerializer {

    public static final int BUFFER_SIZE = 256;

    private static final Field BUFFER = bufferField();

    public LargeFrameSerializer() {
        try {
            BUFFER.set(this, new int[BUFFER_SIZE]);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field bufferField() {
        try {
            Field field = DefaultSerializer.class.getDeclaredField("buffer");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(
                    "zsmartsystems DefaultSerializer no longer has a 'buffer' "
                    + "field, LargeFrameSerializer needs updating", e);
        }
    }
}
