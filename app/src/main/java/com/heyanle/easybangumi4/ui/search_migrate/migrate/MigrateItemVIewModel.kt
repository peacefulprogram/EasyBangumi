package com.heyanle.easybangumi4.ui.search_migrate.migrate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heyanle.easybangumi4.cartoon.entity.CartoonInfo
import com.heyanle.easybangumi4.cartoon.entity.PlayLineWrapper
import com.heyanle.easybangumi4.cartoon.repository.db.dao.CartoonInfoDao
import com.heyanle.easybangumi4.case.SourceStateCase
import com.heyanle.easybangumi4.source_api.component.search.SearchComponent
import com.heyanle.easybangumi4.source_api.entity.CartoonCover
import com.heyanle.easybangumi4.source_api.entity.CartoonSummary
import com.heyanle.easybangumi4.source_api.entity.Episode
import com.heyanle.easybangumi4.source_api.entity.PlayLine
import com.heyanle.injekt.core.Injekt
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Created by heyanle on 2023/12/23.
 * https://github.com/heyanLE
 */
class MigrateItemVIewModel(
    private val cartoonInfo: CartoonInfo,
    private val defSources: List<String>,
) : ViewModel() {


    data class MigrateItemState(
        val isLoadingCover: Boolean = true,
        val isLoadingPlay: Boolean = true,

        // 目标的番剧数据
        val cartoon: CartoonCover? = null,
        val playLineList: List<PlayLine> = emptyList(),

        // 待同步的播放数据
        val sortKey: String = PlayLineWrapper.SORT_DEFAULT_KEY,
        val playLine: PlayLine? = null,
        val episode: Episode? = null,
    )

    private val _flow = MutableStateFlow<MigrateItemState>(MigrateItemState())
    val flow = _flow.asStateFlow()

    private val sourceStateCase: SourceStateCase by Injekt.injectLazy()
    private val cartoonInfoDao: CartoonInfoDao by Injekt.injectLazy()

    private var initJob: Job? = null
    private var loadPlayJob: Job? = null

    init {
        // 收藏的番才能迁移
        if (cartoonInfo.starTime != 0L) {
            init()
        }
    }

    // 1. 搜索目标番剧
    // 用所有源搜索番名，取一页内容，如果一页少于 3 个则往后找知道 3 个。
    // 按照最短编辑距离 - 源优先级两个维度排序
    // 取第一个
    private fun init() {
        initJob?.cancel()
        initJob = viewModelScope.launch {
            _flow.update {
                it.copy(
                    isLoadingCover = true,
                )
            }
            val bundle = sourceStateCase.awaitBundle()
            val res = defSources.map {
                bundle.search(it)
            }.filterIsInstance<SearchComponent>()
                .map { component ->
                    async {
                        val first = component.getFirstSearchKey(cartoonInfo.name)
                        val res = arrayListOf<CartoonCover>()
                        var currentKey: Int? = first
                        while (res.size < 3 && currentKey != null) {
                            component.search(currentKey, cartoonInfo.name)
                                .complete {
                                    yield()
                                    res += it.data.second
                                    currentKey = it.data.first
                                }
                                .error {
                                    yield()
                                    it.throwable.printStackTrace()
                                    currentKey = null
                                }
                        }
                        res
                    }
                }.map {
                    it.await()
                }.flatten()
                .let {
                    if (it.isEmpty()) {
                        null
                    } else {
                        val first = it.first()
                        var min = minDistance(first.title, cartoonInfo.name) to first
                        for (i in 1 until it.size) {
                            val cur = it[i]
                            val current = minDistance(cur.title, cartoonInfo.name) to cur
                            if (current.first < min.first) {
                                min = current
                            } else if (current.first == min.first && defSources.indexOf(current.second.source) < defSources.indexOf(
                                    min.second.source
                                )
                            ) {
                                min = current
                            }
                        }
                        min.second
                    }

                }
            if (res == null) {
                _flow.update {
                    it.copy(
                        isLoadingCover = false,
                        isLoadingPlay = false,
                        cartoon = null,
                    )
                }
            } else {
                _flow.update {
                    it.copy(
                        isLoadingCover = false,
                        cartoon = res,
                    )
                }
                changeCover(res)
            }

        }
    }

    // 2. 获取确定的目标番剧的播放线路，如果用户手动选择目标番剧可以重做这一步
    // 直接用源获取所有播放线路
    // 然后自动确定迁移进度，优先匹配集数一样的线路，集数优先匹配 order 字段
    fun changeCover(cartoonCover: CartoonCover) {
        loadPlayJob?.cancel()
        loadPlayJob = viewModelScope.launch {
            val bundle = sourceStateCase.awaitBundle()
            val detailed = bundle.detailed(cartoonCover.source)
            if (detailed == null) {
                _flow.update {
                    it.copy(
                        isLoadingPlay = false,
                        playLineList = emptyList(),
                    )
                }
                return@launch
            }
            detailed.getPlayLine(
                CartoonSummary(
                    cartoonCover.id,
                    cartoonCover.source,
                    cartoonCover.url
                )
            )
                .complete { complete ->
                    yield()
                    _flow.update {
                        it.copy(
                            isLoadingPlay = false,
                            playLineList = complete.data,
                        )
                    }
                    yield()
                    val oldPlayState = cartoonInfo.matchHistoryEpisode
                    if (oldPlayState == null) {
                        val first = complete.data.firstOrNull()
                        if (first != null) {
                            changeEpisode(
                                PlayLineWrapper.SORT_DEFAULT_KEY,
                                first,
                                first.episode.firstOrNull()
                            )
                        }
                    } else {
                        val playLine =
                            complete.data.find { it.episode.size == oldPlayState.first.playLine.episode.size }
                                ?: complete.data.firstOrNull()
                        if (playLine != null) {
                            val episode =
                                playLine.episode.find { it.order == oldPlayState.second.order }
                                    ?: playLine.episode.getOrNull(
                                        oldPlayState.first.playLine.episode.indexOf(
                                            oldPlayState.second
                                        )
                                    )
                            changeEpisode(PlayLineWrapper.SORT_DEFAULT_KEY, playLine, episode)
                        }
                    }
                }
                .error {
                    yield()
                    _flow.update {
                        it.copy(
                            isLoadingPlay = false,
                            playLineList = emptyList(),
                        )
                    }
                }
        }
    }

    // 3. 确定迁移后的播放进度
    // 如果用户手动选择可以重做这一步
    fun changeEpisode(sortKey: String, playLine: PlayLine, episode: Episode?) {
        val epi = episode ?: playLine.episode.firstOrNull()
        _flow.update {
            it.copy(
                sortKey = sortKey,
                playLine = playLine,
                episode = epi
            )
        }
    }


    private fun minDistance(a: String, b: String): Int {
        val na = a.length
        val nb = b.length
        val dp = Array(na + 1) {
            Array(nb + 1) { 0 }
        }
        for (j in 1..nb) {
            dp[0][j] = dp[0][j - 1] + 1
        }
        for (i in 1..na) {
            dp[i][0] = dp[i - 1][0] + 1
        }
        for (i in 1..na) {
            for (j in 1..nb) {
                if (a[i - 1] == b[j - 1]) dp[i][j] = dp[i - 1][j - 1];
                else dp[i][j] = dp[i - 1][j - 1].coerceAtMost(dp[i][j - 1])
                    .coerceAtMost(dp[i - 1][j]) + 1
            }
        }
        return dp[na][nb]
    }


}