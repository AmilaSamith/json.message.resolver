# Json Message Resolver for WSO2 Micro Integrator (WSO2 MI)

This project provides a custom Log4j2 resolver for formatting log messages as JSON in WSO2 Micro Integrator (WSO2 MI). 
You can use this as a reference project to implement your custom logging requirements.

## Prerequisites
- WSO2 Micro Integrator (WSO2 MI) 4.x or later
- Java 8 or above
- Built `json.message.resolver-1.0.jar` from this project

## Installation
1. **Build the resolver JAR**
   - Run `mvn clean package` in the project root to generate `json.message.resolver-1.0.jar` in the `target/` directory.

2. **Copy the JAR to WSO2 MI**
   - Place `json.message.resolver-1.0.jar` in the `<MI_HOME>/dropins/` directory.

3. **Update Log4j2 configuration**
   - Edit `<MI_HOME>/conf/log4j2.properties` (or the relevant Log4j2 config file).
   - Add or update the appender layout as follows:

    ```
    appender.CARBON_CONSOLE.layout.type = JsonTemplateLayout
    appender.CARBON_CONSOLE.layout.eventTemplate = {"Date":{"$resolver":"timestamp","pattern":{"format":"yyyy-MM-dd HH:mm:ss,SSS"}},"Level":{"$resolver":"level","field":"name"},"Component":{"$resolver":"logger","field":"name"},"Message":{"$resolver":"JsonMessage", "components": ["org.apache.synapse.mediators.builtin.LogMediator"]}}
    ```

4. **Restart WSO2 MI Server**
   - cd `<MI_HOME>/bin/`
   - Run `./micro-integrator.sh`

## Example
With the above configuration, logs from the specified components will be formatted as JSON using the custom resolver.

## Troubleshooting
- Ensure the JAR is in the correct `lib` directory.
- Restart WSO2 MI after making changes.
- Check logs for errors related to Log4j2 plugin loading.

## License
Apache License, Version 2.0
