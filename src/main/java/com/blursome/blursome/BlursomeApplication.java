package com.blursome.blursome;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BlursomeApplication {

  public static void main(String[] args) {
    SpringApplication.run(BlursomeApplication.class, args);
  }

}
