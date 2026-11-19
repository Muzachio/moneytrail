package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** backend/fly.toml, which the tests read so they check what Fly.io really runs. */
final class FlyToml {

    private FlyToml() {}

    /**
     * The arguments Fly.io passes to the image's {@code java -jar moneytrail.jar} for a process
     * ({@code api}, {@code worker}) or the {@code release_command}.
     */
    static List<String> arguments(String key) throws IOException {
        Matcher value = Pattern.compile("(?m)^\\s*" + key + "\\s*=\\s*\"([^\"]*)\"")
                .matcher(Files.readString(Path.of("fly.toml")));
        assertThat(value.find()).as("%s in fly.toml", key).isTrue();
        return List.of(value.group(1).trim().split("\\s+"));
    }
}
