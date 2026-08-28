package net.shieldshare.shieldshare;

import net.shieldshare.shieldshare.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ShieldshareApplicationTests extends AbstractPostgresIT {

	@Test
	void contextLoads() {
	}

}
