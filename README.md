![PORT-MERCURY Graphic](https://spaceport.com.co/assets/port-mercury-graphic.png "PORT-MERCURY Graphic")


# Port-Mercury
Port-Mercury is a Spaceport starter kit that provides a basic app structure and configuration for building a 
Spaceport application with some key Spaceport features.

See also: [Scaffolds](https://spaceport.com.co/docs/scaffolds#port-mercury) for more information.


## Getting Started
This starter kit is a great choice for a single-tenant application, such as a website or personal web application.
It offers a basic structure that includes the key components of Spaceport, including routing, authentication,
database access, reactivity, and more.

<!-- If you are looking for a more complex configuration that may share resources with other applications, you may want to
consider using the [Port-Gemini](https://spaceport.com.co/docs/scaffolds#gemini) starter kit instead. -->

<!-- If you are looking for a multi-tenant application that has multiple users or groups, 
[Port-Voyager](https://spaceport.com.co/docs/scaffolds#voyager) might be a better place to start. -->

Developer Onboarding: [https://spaceport.com.co/docs/developer-onboarding](https://spaceport.com.co/docs/developer-onboarding)


## Pre-requisites
- Java 8 or higher
- CouchDB 2.0 or higher



## Features
- Basic structure and configuration for a robust single-tenant Spaceport application
- Routing with basic error handling (4XX and 5XX)
- Authentication for administrator access
- Launchpad scaffolding for server-side HTML and reactivity
- Database access with Documents and Cargo
- Ready to launch, take the helm


## Startup
To start the application, run the following command:

```bash
java -jar spaceport.jar --start config.spaceport
```

This will start the application using the configuration file `config.spaceport`.

Don't have Spaceport? You can download it from the [Spaceport website](https://spaceport.com.co/builds/). Or, use
the following command to grab the latest version:

```bash 
curl -L https://spaceport.com.co/builds/spaceport-latest.jar -o spaceport.jar
```


## Learn more
For more information about Spaceport, visit the [Spaceport documentation](https://spaceport.com.co/docs).
