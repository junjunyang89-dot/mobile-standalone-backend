package com.mobile.standalone.config;

import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.spi.JobFactory;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

/**
 * Quartz 配置
 * 配置 Quartz 调度器，支持 Spring Bean 注入
 */
@Configuration
public class QuartzConfig {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    /**
     * 创建 JobFactory，支持 Spring Bean 注入
     */
    @Bean
    public JobFactory jobFactory() {
        AutowiringSpringBeanJobFactory jobFactory = new AutowiringSpringBeanJobFactory();
        jobFactory.setApplicationContext(applicationContext);
        return jobFactory;
    }
    
    /**
     * 创建 SchedulerFactoryBean
     */
    @Bean
    public SchedulerFactoryBean schedulerFactoryBean() throws SchedulerException {
        SchedulerFactoryBean factory = new SchedulerFactoryBean();
        factory.setJobFactory(jobFactory());
        factory.setOverwriteExistingJobs(true);
        factory.setStartupDelay(10); // 延迟10秒启动，等待应用完全启动
        return factory;
    }
    
    /**
     * 创建 Scheduler
     */
    @Bean
    public Scheduler scheduler() throws SchedulerException {
        return schedulerFactoryBean().getScheduler();
    }
    
    /**
     * 自定义 JobFactory，支持 Spring Bean 注入
     */
    public static class AutowiringSpringBeanJobFactory extends SpringBeanJobFactory {
        @Autowired
        private ApplicationContext applicationContext;
        
        @Override
        protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
            Object job = super.createJobInstance(bundle);
            applicationContext.getAutowireCapableBeanFactory().autowireBean(job);
            return job;
        }
    }
}
