#import "ARListViewController.h"

/// YouTube search through the proxy, plus saved and recently played tracks.
@interface ARStreamViewController : ARListViewController
@property (nonatomic, copy) void (^onOpenSettings)(void);
@end
