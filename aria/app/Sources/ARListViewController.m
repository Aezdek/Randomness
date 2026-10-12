#import "ARListViewController.h"
#import "ARTrackCell.h"
#import "ARPlayer.h"
#import "ARTrack.h"
#import "ARTheme.h"
#import "ARTrackActions.h"
#import "ARMiniPlayerView.h"

@implementation ARListViewController {
    UILabel *_titleLabel;
    UILabel *_navTitleLabel;
    void (^_emptyAction)(void);
    BOOL _didSetInitialOffset;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [ARTheme background];
    self.automaticallyAdjustsScrollViewInsets = NO;

    _tableView = [[UITableView alloc] initWithFrame:self.view.bounds style:UITableViewStylePlain];
    _tableView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    _tableView.dataSource = self;
    _tableView.delegate = self;
    _tableView.rowHeight = [ARTrackCell height];
    _tableView.backgroundColor = [ARTheme background];
    _tableView.separatorColor = [ARTheme separator];
    _tableView.separatorInset = UIEdgeInsetsMake(0, 76, 0, 0);
    _tableView.keyboardDismissMode = UIScrollViewKeyboardDismissModeOnDrag;
    _tableView.tableFooterView = [UIView new];
    _tableView.indicatorStyle = UIScrollViewIndicatorStyleWhite;
    [_tableView registerClass:[ARTrackCell class] forCellReuseIdentifier:@"track"];
    [self.view addSubview:_tableView];

    _navTitleLabel = [UILabel new];
    _navTitleLabel.font = [ARTheme fontOfSize:17 weight:UIFontWeightSemibold];
    _navTitleLabel.textColor = [ARTheme textPrimary];
    _navTitleLabel.alpha = 0;

    UILongPressGestureRecognizer *press = [[UILongPressGestureRecognizer alloc] initWithTarget:self action:@selector(longPress:)];
    [_tableView addGestureRecognizer:press];

    [self rebuildHeader];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(playerDidChange) name:ARPlayerDidChangeNotification object:nil];
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

- (void)setLargeTitle:(NSString *)largeTitle {
    _largeTitle = [largeTitle copy];
    self.title = largeTitle;
    if (self.isViewLoaded) [self rebuildHeader];
}

- (void)rebuildHeader {
    CGFloat width = self.view.bounds.size.width;
    UIView *header = [[UIView alloc] initWithFrame:CGRectMake(0, 0, width, 0)];
    CGFloat y = 6;
    if (self.largeTitle.length) {
        if (!_titleLabel) {
            _titleLabel = [UILabel new];
            _titleLabel.font = [ARTheme fontOfSize:34 weight:UIFontWeightBold];
            _titleLabel.textColor = [ARTheme textPrimary];
        }
        _titleLabel.text = self.largeTitle;
        _titleLabel.frame = CGRectMake(16, y, width - 32, 41);
        [header addSubview:_titleLabel];
        y += 41 + 8;
    }
    _navTitleLabel.text = self.largeTitle;
    [_navTitleLabel sizeToFit];
    self.navigationItem.titleView = self.largeTitle.length ? _navTitleLabel : nil;
    if (self.headerAccessoryView) {
        CGRect f = self.headerAccessoryView.frame;
        f.origin.y = y;
        f.size.width = width;
        self.headerAccessoryView.frame = f;
        self.headerAccessoryView.autoresizingMask = UIViewAutoresizingFlexibleWidth;
        [header addSubview:self.headerAccessoryView];
        y += f.size.height;
    }
    header.frame = CGRectMake(0, 0, width, y + 4);
    self.tableView.tableHeaderView = header;
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    [self updateInsets];
}

- (void)updateInsets {
    CGFloat top = self.topLayoutGuide.length;
    CGFloat bottom = self.bottomLayoutGuide.length;
    if ([ARPlayer shared].currentTrack && self.tabBarController) bottom += [ARMiniPlayerView height];
    UIEdgeInsets insets = UIEdgeInsetsMake(top, 0, bottom, 0);
    if (!UIEdgeInsetsEqualToEdgeInsets(insets, self.tableView.contentInset)) {
        self.tableView.contentInset = insets;
        self.tableView.scrollIndicatorInsets = insets;
    }
    if (!_didSetInitialOffset && top > 0) {
        _didSetInitialOffset = YES;
        self.tableView.contentOffset = CGPointMake(0, -top);
    }
}

- (void)scrollViewDidScroll:(UIScrollView *)scrollView {
    if (scrollView != self.tableView || !self.largeTitle.length) return;
    CGFloat offset = scrollView.contentOffset.y + scrollView.contentInset.top;
    _navTitleLabel.alpha = MIN(1, MAX(0, (offset - 30) / 16));
}

#pragma mark - Player

- (ARTrack *)trackAtIndexPath:(NSIndexPath *)indexPath { return nil; }

- (void)playerDidChange {
    [self refreshPlayingIndicators];
    [self.view setNeedsLayout];
}

- (void)refreshPlayingIndicators {
    ARPlayer *player = [ARPlayer shared];
    ARTrack *current = player.currentTrack;
    for (NSIndexPath *ip in self.tableView.indexPathsForVisibleRows) {
        UITableViewCell *cell = [self.tableView cellForRowAtIndexPath:ip];
        if (![cell isKindOfClass:[ARTrackCell class]]) continue;
        ARTrack *track = [self trackAtIndexPath:ip];
        [(ARTrackCell *)cell setCurrent:(track && [track isSameTrack:current]) playing:player.playing];
    }
}

- (void)tableView:(UITableView *)tableView willDisplayCell:(UITableViewCell *)cell forRowAtIndexPath:(NSIndexPath *)indexPath {
    if (![cell isKindOfClass:[ARTrackCell class]]) return;
    ARTrack *track = [self trackAtIndexPath:indexPath];
    ARPlayer *player = [ARPlayer shared];
    [(ARTrackCell *)cell setCurrent:(track && [track isSameTrack:player.currentTrack]) playing:player.playing];
}

- (void)longPress:(UILongPressGestureRecognizer *)press {
    if (press.state != UIGestureRecognizerStateBegan) return;
    NSIndexPath *ip = [self.tableView indexPathForRowAtPoint:[press locationInView:self.tableView]];
    ARTrack *track = ip ? [self trackAtIndexPath:ip] : nil;
    if (!track) return;
    [ARTrackActions presentForTrack:track from:self sourceView:[self.tableView cellForRowAtIndexPath:ip]];
}

#pragma mark - Table defaults

- (NSInteger)tableView:(UITableView *)tableView numberOfRowsInSection:(NSInteger)section { return 0; }

- (UITableViewCell *)tableView:(UITableView *)tableView cellForRowAtIndexPath:(NSIndexPath *)indexPath {
    return [tableView dequeueReusableCellWithIdentifier:@"track" forIndexPath:indexPath];
}

- (UIView *)sectionHeaderWithTitle:(NSString *)title {
    UIView *v = [[UIView alloc] initWithFrame:CGRectMake(0, 0, 320, 40)];
    v.backgroundColor = [ARTheme background];
    UILabel *label = [[UILabel alloc] initWithFrame:CGRectMake(16, 12, 288, 26)];
    label.autoresizingMask = UIViewAutoresizingFlexibleWidth;
    label.text = title;
    label.font = [ARTheme fontOfSize:20 weight:UIFontWeightBold];
    label.textColor = [ARTheme textPrimary];
    [v addSubview:label];
    return v;
}

#pragma mark - Empty state

- (void)showEmptyStateWithTitle:(NSString *)title message:(NSString *)message buttonTitle:(NSString *)buttonTitle action:(void (^)(void))action {
    _emptyAction = [action copy];
    UIView *container = [[UIView alloc] initWithFrame:self.tableView.bounds];
    CGFloat width = container.bounds.size.width - 64;

    UILabel *titleLabel = [UILabel new];
    titleLabel.text = title;
    titleLabel.font = [ARTheme fontOfSize:20 weight:UIFontWeightSemibold];
    titleLabel.textColor = [ARTheme textPrimary];
    titleLabel.textAlignment = NSTextAlignmentCenter;
    titleLabel.numberOfLines = 0;
    CGFloat th = ceil([titleLabel sizeThatFits:CGSizeMake(width, CGFLOAT_MAX)].height);

    UILabel *messageLabel = [UILabel new];
    messageLabel.text = message;
    messageLabel.font = [ARTheme fontOfSize:15 weight:UIFontWeightRegular];
    messageLabel.textColor = [ARTheme textSecondary];
    messageLabel.textAlignment = NSTextAlignmentCenter;
    messageLabel.numberOfLines = 0;
    CGFloat mh = ceil([messageLabel sizeThatFits:CGSizeMake(width, CGFLOAT_MAX)].height);

    CGFloat headerBottom = self.tableView.tableHeaderView.frame.size.height + self.tableView.contentInset.top;
    CGFloat available = container.bounds.size.height - headerBottom - self.tableView.contentInset.bottom;
    CGFloat total = th + 8 + mh + (buttonTitle ? 56 : 0);
    CGFloat y = headerBottom + MAX(16, (available - total) / 2);

    titleLabel.frame = CGRectMake(32, y, width, th);
    messageLabel.frame = CGRectMake(32, y + th + 8, width, mh);
    [container addSubview:titleLabel];
    [container addSubview:messageLabel];

    if (buttonTitle) {
        UIButton *button = [UIButton buttonWithType:UIButtonTypeCustom];
        [button setTitle:buttonTitle forState:UIControlStateNormal];
        button.titleLabel.font = [ARTheme fontOfSize:15 weight:UIFontWeightSemibold];
        [button setBackgroundImage:[ARTheme pillImageWithColor:[ARTheme accent] height:36] forState:UIControlStateNormal];
        button.contentEdgeInsets = UIEdgeInsetsMake(0, 20, 0, 20);
        [button sizeToFit];
        button.frame = CGRectMake((container.bounds.size.width - button.bounds.size.width) / 2, CGRectGetMaxY(messageLabel.frame) + 20, button.bounds.size.width, 36);
        [button addTarget:self action:@selector(emptyButtonTapped) forControlEvents:UIControlEventTouchUpInside];
        [container addSubview:button];
    }
    self.tableView.backgroundView = container;
}

- (void)emptyButtonTapped {
    if (_emptyAction) _emptyAction();
}

- (void)hideEmptyState {
    self.tableView.backgroundView = nil;
    _emptyAction = nil;
}

@end
