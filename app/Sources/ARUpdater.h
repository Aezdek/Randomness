#import <UIKit/UIKit.h>

/// Checks GitHub for a newer Aria. An app can't install .debs itself (that needs root), so it offers
/// to open the download in Safari, which hands it to Filza.
@interface ARUpdater : NSObject
/// At most every 6 hours unless forced. Forced checks also say when you're up to date.
+ (void)checkFrom:(UIViewController *)controller force:(BOOL)force;
@end
