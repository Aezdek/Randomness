#import "ARAppDelegate.h"
#import "ARTabBarController.h"
#import "ARLibraryViewController.h"
#import "ARStreamViewController.h"
#import "ARSettingsViewController.h"
#import "ARPlayer.h"
#import "ARTheme.h"

@implementation ARAppDelegate

- (UINavigationController *)navWithRoot:(UIViewController *)root {
    UINavigationController *nav = [[UINavigationController alloc] initWithRootViewController:root];
    nav.tabBarItem = root.tabBarItem;
    return nav;
}

- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    [ARTheme applyAppearance];
    [ARPlayer shared];
    [application beginReceivingRemoteControlEvents];

    ARTabBarController *tabs = [ARTabBarController new];
    ARStreamViewController *stream = [ARStreamViewController new];
    __weak ARTabBarController *weakTabs = tabs;
    stream.onOpenSettings = ^{ weakTabs.selectedIndex = 2; };
    tabs.viewControllers = @[
        [self navWithRoot:[ARLibraryViewController new]],
        [self navWithRoot:stream],
        [self navWithRoot:[ARSettingsViewController new]],
    ];

    self.window = [[UIWindow alloc] initWithFrame:[UIScreen mainScreen].bounds];
    self.window.backgroundColor = [ARTheme background];
    self.window.tintColor = [ARTheme accent];
    self.window.rootViewController = tabs;
    [self.window makeKeyAndVisible];
    return YES;
}

@end
