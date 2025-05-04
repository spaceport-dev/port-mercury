import spaceport.Spaceport
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult
import spaceport.computer.alerts.results.Result
import spaceport.launchpad.Launchpad

/**
 *
 *   _              /_
 * _)/)(/(_(-/)()/-/   PORT-MERCURY, a basic Spaceport scaffold
 *  /       /    v2
 *
 * Port-Mercury is a Spaceport starter kit that provides a basic app structure and configuration for building a
 * Spaceport application with some key Spaceport features.
 *
 * This scaffold is a great starting point for a single-tenant web application, or a website that needs an
 * administration interface. If you're looking to build a multi-tenant application, you may want to
 * consider using the Port-Voyager starter kit instead.
 *
 * See also: https://www.spaceport.com.co/docs/scaffolds#mercury
 *
 */

class App {


    @Alert('on initialized')
    static _init(Result r) {

        // Make sure the 'mercury' database exists, we'll use this for some default
        // database documents through the application. Your app may need additional
        // databases. They can be created here, or initialized as a migration.
        if (!Spaceport.main_memory_core.containsDatabase('mercury'))
            Spaceport.main_memory_core.createDatabase('mercury')
    }


    // Use Launchpad to render login/logout pages.
    static Launchpad launchpad = new Launchpad()


    // Redirect root to index.html route
    @Alert('on / hit')
    static _root(HttpResult r) {
        r.setRedirectUrl('/index.html')
    }


    //
    // Standard Page Routing
    //


    // Handle calls to /index.html explicitly
    @Alert('on /index.html hit')
    static _index(HttpResult r) {

        // Pass off to launchpad to render the index.ghtml file inside of wrapper.ghtml
        launchpad.assemble(['index.ghtml']).launch(r, 'wrapper.ghtml')
    }


    // Handle sub-pages with regular expression matches
    @Alert('~on /([^/]+)\\.html hit')   // This matches the name of the html page, disallowing sub-folders
    static _pages(HttpResult r) {

        // Use the page name match to route the correct file
        def pageName = r.matches[0]
        if (pageName == 'index') return // Already handled. Alerts can stack!
        launchpad.assemble(["pages/${ pageName }.ghtml"]).launch(r, 'wrapper.ghtml')
    }


    //
    // Administrator Panel Routing
    //

    @Alert('on /a/ hit')
    static _admin(HttpResult r) {

        // Use the 'administratorAuthPlug' provided by Router.groovy
        // to ensure the user is authenticated and authorized.
        // If not, the plug will send the user to the login page
        // with a redirect url to come back to this route.
        if (r.authorize(Router.administratorAuthPlug)) {
            launchpad.assemble(['admin/overview.ghtml']).launch(r)
        }
    }


}