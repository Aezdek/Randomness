#import "ARUpdater.h"

static NSString *const ARVersionURL = @"https://github.com/Aezdek/Randomness/releases/download/aria-v1.0.0/ios-version.json";
static NSString *const ARDebURL = @"https://github.com/Aezdek/Randomness/releases/download/aria-v1.0.0/xyz.aezdek.aria_1.0.0_iphoneos-arm.deb";
static NSString *const ARLastCheckKey = @"ARLastUpdateCheck";

@implementation ARUpdater

+ (void)alert:(UIViewController *)controller title:(NSString *)title message:(NSString *)message installURL:(NSURL *)url {
    UIAlertController *alert = [UIAlertController alertControllerWithTitle:title message:message preferredStyle:UIAlertControllerStyleAlert];
    if (url) {
        [alert addAction:[UIAlertAction actionWithTitle:@"Later" style:UIAlertActionStyleCancel handler:nil]];
        [alert addAction:[UIAlertAction actionWithTitle:@"Install" style:UIAlertActionStyleDefault handler:^(UIAlertAction *a) {
            [[UIApplication sharedApplication] openURL:url];
        }]];
    } else {
        [alert addAction:[UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleCancel handler:nil]];
    }
    UIViewController *top = controller;
    while (top.presentedViewController) top = top.presentedViewController;
    [top presentViewController:alert animated:YES completion:nil];
}

+ (void)checkFrom:(UIViewController *)controller force:(BOOL)force {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    NSTimeInterval last = [defaults doubleForKey:ARLastCheckKey];
    if (!force && [[NSDate date] timeIntervalSince1970] - last < 6 * 3600) return;
    [defaults setDouble:[[NSDate date] timeIntervalSince1970] forKey:ARLastCheckKey];

    NSURLRequest *request = [NSURLRequest requestWithURL:[NSURL URLWithString:ARVersionURL] cachePolicy:NSURLRequestReloadIgnoringLocalCacheData timeoutInterval:15];
    [[[NSURLSession sharedSession] dataTaskWithRequest:request completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        NSDictionary *json = data.length ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
        NSString *latest = [json isKindOfClass:[NSDictionary class]] && [json[@"version"] isKindOfClass:[NSString class]] ? json[@"version"] : nil;
        NSString *installed = [NSBundle mainBundle].infoDictionary[@"CFBundleShortVersionString"];
        dispatch_async(dispatch_get_main_queue(), ^{
            if (!latest) {
                if (force) [self alert:controller title:@"Couldn't check for updates" message:error.localizedDescription ?: @"GitHub didn't answer." installURL:nil];
                return;
            }
            if ([latest compare:installed options:NSNumericSearch] == NSOrderedDescending) {
                NSString *link = [json[@"url"] isKindOfClass:[NSString class]] ? json[@"url"] : ARDebURL;
                [self alert:controller
                      title:[NSString stringWithFormat:@"Aria %@ is available", latest]
                    message:@"Safari downloads it. Then tap Open in \"Filza\" and Install."
                 installURL:[NSURL URLWithString:link]];
            } else if (force) {
                [self alert:controller title:@"Aria is up to date" message:[NSString stringWithFormat:@"Version %@", installed] installURL:nil];
            }
        });
    }] resume];
}

@end
