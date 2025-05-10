import spaceport.Spaceport
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult
import spaceport.launchpad.Launchpad
import spaceport.personnel.Client


/**
 *
 *   _              /_
 * _)/)(/(_(-/)()/-/   PORT-MERCURY, a basic Spaceport scaffold
 *  /       /    v2
 *
 *
 * This Router class provides basic routing functionality for some common boilerplate when dealing
 * with a web application, such as login/logout pages for authentication, favicon.ico, PWA manifest,
 * and custom 4XX and 5XX error pages.
 *
 * See also: https://www.spaceport.com.co/docs/scaffolds#mercury
 *
 */

class Router {


    //
    // Authentication and authorization
    //


    // The administratorAuthPlug is an authorization plug that checks if the user is
    // authenticated and has the 'spaceport-administrator' permission.
    // Use this plug to redirect users to the login page if they are not authorized.
    static def administratorAuthPlug = { HttpResult r ->

        // Use the client to check if the user is an administrator
        def client = r.client

        if (client.isAuthenticated() && client.document.hasPermission('spaceport-administrator')) {
            // Client has an authenticated spaceport-uuid cookie, and the user document associated
            // has the 'spaceport-administrator' permission. The client is 'authorized'.
            return true
        } else {
            // Redirect to the login page with a queryString message, and a redirect URL
            // back to the original location.
            def message = 'You must be logged in as an administrator to access this page.'
            r.setRedirectUrl('/login?message=' + message.encode() + '&redirect=' + r.context.target.encode())
            return false
        }

    }


    // Use Launchpad to render login/logout pages.
    static Launchpad launchpad = new Launchpad()


    // Provide a /login route for a login page.
    @Alert('on /login GET')
    static def loginPage(HttpResult r) {
        // Render the login page using the Launchpad
        launchpad.assemble(['admin/login.ghtml']).launch(r)
    }


    // Process a /login POST request to authenticate the user.
    @Alert('on /login POST')
    static def login(HttpResult r) {
        String username = r.context.data.username
        String password = r.context.data.password
        // Get the redirect URL from the request context, or default to '/'
        String redirectUrl = r.context.data.redirect ?: '/'

        // Attempt to authenticate the user.
        def authorized = Client.getAuthenticatedClient(username, password)
        if (authorized) {
            // Authentication successful, attach the user's cookie so future requests can be authenticated
            authorized.attachCookie(r.context.cookies.'spaceport-uuid' as String)
            r.setRedirectUrl(redirectUrl)
        } else {
            // Authentication failed, redirect to the login page with an error message
            def message = 'Invalid username or password. Try again?'
            r.setRedirectUrl('/login?message=' + message.encode() + '&redirect=' + redirectUrl.encode())
        }
    }


    // Provide a /logout route for logging out the user.
    @Alert('on /logout hit')
    static def logout(HttpResult r) {
        // Logout the user by clearing the spaceport-uuid cookie
        def client = r.client
        def redirect = r.context.data.redirect ?: '/'

        if (client.isAuthenticated()) {
            client.removeCookie(r.context.cookies.'spaceport-uuid' as String)
            r.setRedirectUrl('/login?message=You have been logged out.&redirect=' + redirect.encode())
        } else {
            r.setRedirectUrl('/')
        }
    }


    //
    // PWA Manifest Generator
    //


    // Provide a /manifest.webmanifest file with relevant information.
    @Alert('on /manifest.webmanifest hit')
    static _manifest(HttpResult r) {
        // Use the Spaceport configuration to set/get the PWA settings
        def config = Spaceport.config.'PWA'

        // Check if the PWA node is enabled
        if (!config.enabled) {
            r.setStatus(404)
            return
        }

        // Generate PWA Web Manifest with information from the configuration file, or default values.
        // Note: This is not an exhaustive list of all possible PWA manifest properties. This setup
        // also assumes the config has a name and at least one icon defined in the PWA section.
        def manifest = """
            {
                "name": "${ config.'name' }",
                "short_name": "${ config.'short name' ?: config.'name' }",
                "description": "${ config.'description' ?: '' }",
                "id": "${ config.'name' }",
                "start_url": "${ config.'start url' ?: "/?source=pwa" }",
                "scope": "${ config.'scope' ?: "/" }",
                "display": "${ config.'display' ?: "standalone" }",
                "background_color": "${ config.'background color' ?: "#ffffff" }",
                "theme_color": "${ config.'theme color' ?: "#ffffff" }",
                "orientation": "${ config.'orientation' ?: "any" }",
                "icons": [
                    ${ config.icons.collect { icon ->
                    """
                    {
                        "src":   "${ icon.src }",
                        "sizes": "${ icon.sizes }",
                        "type":  "${ icon.type }"
                    }
                    """
                    }.join(",") }
                ]
            }
        """.stripIndent()

        // Note: The manifest is a "JSON" String, so we need to set the content type to application/json
        r.setContentType('application/manifest+json')
        r.writeToClient(manifest)
    }


    //
    // Favicon.ico fallback
    //


    // This is not guaranteed to work on all browsers, but it is a fallback for most
    // modern browsers. If you need more control over the favicon, you should use a meta tag.
    @Alert('on /favicon.ico hit')
    static _favicon(HttpResult r) {
        // Write the favicon file to the client
        r.writeToClient(Spaceport.getSpaceportRootFile('assets/img/icon.svg'))
    }


    //
    // 4XX and 5XX custom error pages
    //


    // Alerts can stack. 'on page hit' runs after an explicit 'on ... hit'
    @Alert('on page hit')
    static _checkStatusCode(HttpResult r) {
        if (r.context.response.status >= 500) {
            // Something went wrong, and there was a 5XX error.
            launchpad.assemble(['error-pages/5XX.ghtml']).launch(r)
        } else if (r.context.response.status >= 400) {
            // No alerts addressed the page hit, or some other
            // 4XX error was set.
            launchpad.assemble(['error-pages/4XX.ghtml']).launch(r)
        }
    }

    
}
