#import <UIKit/UIKit.h>

@class ARTrack;

/// Base for the scrolling screens: a big bold title that hands over to the navigation bar
/// as you scroll, insets that clear the mini player, and playing-row indicators.
@interface ARListViewController : UIViewController <UITableViewDataSource, UITableViewDelegate>

@property (nonatomic, readonly) UITableView *tableView;
@property (nonatomic, copy) NSString *largeTitle;
/// Shown under the large title, for example a search bar.
@property (nonatomic, strong) UIView *headerAccessoryView;

/// Rebuilds the header. Call after changing the large title or the accessory view's height.
- (void)rebuildHeader;
/// Subclasses return the track shown at a row, or nil, so the playing row can be marked.
- (ARTrack *)trackAtIndexPath:(NSIndexPath *)indexPath;
- (void)refreshPlayingIndicators;
/// Called when the player changes. The default refreshes the indicators and the insets.
- (void)playerDidChange;

- (void)showEmptyStateWithTitle:(NSString *)title message:(NSString *)message buttonTitle:(NSString *)buttonTitle action:(void (^)(void))action;
- (void)hideEmptyState;

/// Section header styled to match the app.
- (UIView *)sectionHeaderWithTitle:(NSString *)title;

@end
