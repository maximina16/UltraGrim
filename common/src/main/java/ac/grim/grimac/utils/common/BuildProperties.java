package ac.grim.grimac.utils.common;

import lombok.experimental.UtilityClass;

import java.util.Properties;

@UtilityClass
public class BuildProperties {

    private static final boolean SHADE_PE = loadShadePe();

    public static boolean shadesPacketEvents() {
        return SHADE_PE;
    }

    private static boolean loadShadePe() {
        try {
            Properties properties = PropertiesUtil.readProperties(BuildProperties.class, "grimac.properties");
            return Boolean.parseBoolean(properties.getProperty("build.shade_pe", "false"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
