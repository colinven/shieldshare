package net.shieldshare.shieldshare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories
public class ShieldshareApplication {

	public static void main(String[] args) {
		SpringApplication.run(ShieldshareApplication.class, args);
	}

}
