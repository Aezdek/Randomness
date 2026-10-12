#import "ARTabBarController.h"
#import "ARMiniPlayerView.h"
#import "ARNowPlayingViewController.h"
#import "ARPlayer.h"

@implementation ARTabBarController {
    ARMiniPlayerView *_miniPlayer;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    _miniPlayer = [ARMiniPlayerView new];
    __weak typeof(self) weakSelf = self;
    _miniPlayer.onOpen = ^{ [weakSelf presentNowPlaying]; };
    [self.view insertSubview:_miniPlayer belowSubview:self.tabBar];
    _miniPlayer.hidden = YES;
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(playerChanged) name:ARPlayerDidChangeNotification object:nil];
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    CGFloat h = [ARMiniPlayerView height];
    _miniPlayer.frame = CGRectMake(0, CGRectGetMinY(self.tabBar.frame) - h, self.view.bounds.size.width, h);
}

- (void)playerChanged {
    BOOL show = [ARPlayer shared].currentTrack != nil;
    if (show == !_miniPlayer.hidden) return;
    _miniPlayer.hidden = NO;
    CGRect f = _miniPlayer.frame;
    _miniPlayer.transform = show ? CGAffineTransformMakeTranslation(0, f.size.height) : CGAffineTransformIdentity;
    [UIView animateWithDuration:0.35 delay:0 usingSpringWithDamping:0.85 initialSpringVelocity:0 options:0 animations:^{
        self->_miniPlayer.transform = show ? CGAffineTransformIdentity : CGAffineTransformMakeTranslation(0, f.size.height);
    } completion:^(BOOL finished) {
        self->_miniPlayer.hidden = !show;
        self->_miniPlayer.transform = CGAffineTransformIdentity;
    }];
}

- (void)presentNowPlaying {
    if (![ARPlayer shared].currentTrack || self.presentedViewController) return;
    ARNowPlayingViewController *vc = [ARNowPlayingViewController new];
    [self presentViewController:vc animated:YES completion:nil];
}

@end
