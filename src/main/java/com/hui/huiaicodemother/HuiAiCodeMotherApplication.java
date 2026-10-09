package com.hui.huiaicodemother;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.hui.huiaicodemother.mapper")
public class HuiAiCodeMotherApplication {

    public static void main(String[] args) {
        SpringApplication.run(HuiAiCodeMotherApplication.class, args);
    }

}
