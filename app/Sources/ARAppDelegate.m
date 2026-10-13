#import "ARAppDelegate.h"
#import "ARTabBarController.h"
#import "ARLibraryViewController.h"
#import "ARStreamViewController.h"
#import "ARSettingsViewController.h"
#import "ARNowPlayingViewController.h"
#import "ARTrackActions.h"
#import "ARSettings.h"
#import "ARPlayer.h"
#import "ARSync.h"
#import "ARTheme.h"

@implementation ARAppDelegate

- (UINavigationController *)navWithRoot:(UIViewController *)root {
    UINavigationController *nav = [[UINavigationController alloc] initWithRootViewController:root];
    nav.tabBarItem = root.tabBarItem;
    return nav;
}

- (ARTabBarController *)buildTabsSelecting:(NSInteger)index {
    [ARTheme applyAppearance];
    ARTabBarController *tabs = [ARTabBarController new];
    ARStreamViewController *stream = [ARStreamViewController new];
    __weak ARTabBarController *weakTabs = tabs;
    stream.onOpenSettings = ^{ weakTabs.selectedIndex = 2; };
    tabs.viewControllers = @[
        [self navWithRoot:[ARLibraryViewController new]],
        [self navWithRoot:stream],
        [self navWithRoot:[ARSettingsViewController new]],
    ];
    tabs.selectedIndex = index;
    return tabs;
}

- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    [ARPlayer shared];
    [application beginReceivingRemoteControlEvents];

    self.window = [[UIWindow alloc] initWithFrame:[UIScreen mainScreen].bounds];
    self.window.backgroundColor = [ARTheme background];
    self.window.tintColor = [ARTheme accent];
    self.window.rootViewController = [self buildTabsSelecting:[ARSettings shared].startsOnStream ? 1 : 0];
    [self.window makeKeyAndVisible];

    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(settingsChanged:) name:ARSettingsDidChangeNotification object:nil];
    [[ARSync shared] start];
    return YES;
}

/// The look changed (usually from the website): rebuild the screens. The player keeps playing.
- (void)settingsChanged:(NSNotification *)note {
    if (![note.userInfo[@"appearance"] boolValue]) return;
    UITabBarController *old = (UITabBarController *)self.window.rootViewController;
    BOOL nowPlayingOpen = [old.presentedViewController isKindOfClass:[ARNowPlayingViewController class]];
    void (^rebuild)(void) = ^{
        self.window.backgroundColor = [ARTheme background];
        self.window.tintColor = [ARTheme accent];
        ARTabBarController *tabs = [self buildTabsSelecting:old.selectedIndex];
        [UIView transitionWithView:self.window duration:0.3 options:UIViewAnimationOptionTransitionCrossDissolve animations:^{
            self.window.rootViewController = tabs;
        } completion:^(BOOL finished) {
            if (nowPlayingOpen) [tabs presentNowPlaying];
            [ARTrackActions showToast:@"Look updated"];
        }];
    };
    if (old.presentedViewController) [old dismissViewControllerAnimated:NO completion:rebuild];
    else rebuild();
}

@end
