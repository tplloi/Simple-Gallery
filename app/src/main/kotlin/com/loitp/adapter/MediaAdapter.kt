package com.loitp.adapter

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.bumptech.glide.Glide
import com.loitp.ext.*
import com.loitp.pro.R
import com.loitp.ui.dialog.DeleteWithRememberDialog
import com.loitp.pro.helpers.*
import com.loitp.interfaces.MediaOperationsListener
import com.loitp.model.Medium
import com.loitp.model.ThumbnailItem
import com.loitp.model.ThumbnailSection
import com.loitp.ui.activity.ViewPagerActivity
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.adapters.MyRecyclerViewAdapter
import com.simplemobiletools.commons.dialogs.PropertiesDialog
import com.simplemobiletools.commons.dialogs.RenameDialog
import com.simplemobiletools.commons.dialogs.RenameItemDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.FileDirItem
import com.simplemobiletools.commons.views.FastScroller
import com.simplemobiletools.commons.views.MyRecyclerView
import kotlinx.android.synthetic.main.photo_item_grid.view.*
import kotlinx.android.synthetic.main.thumbnail_section.view.*
import kotlinx.android.synthetic.main.video_item_grid.view.*
import kotlinx.android.synthetic.main.video_item_grid.view.media_item_holder
import kotlinx.android.synthetic.main.video_item_grid.view.medium_check
import kotlinx.android.synthetic.main.video_item_grid.view.medium_name
import kotlinx.android.synthetic.main.video_item_grid.view.medium_thumbnail
import java.util.*

class MediaAdapter(
    activity: BaseSimpleActivity,
    var media: ArrayList<ThumbnailItem>,
    val listener: MediaOperationsListener?,
    val isAGetIntent: Boolean,
    val allowMultiplePicks: Boolean,
    val path: String,
    recyclerView: MyRecyclerView,
    fastScroller: FastScroller? = null,
    itemClick: (Any) -> Unit
) :
    MyRecyclerViewAdapter(
        activity = activity,
        recyclerView = recyclerView,
        fastScroller = fastScroller,
        itemClick = itemClick
    ) {

    companion object {
        private const val INSTANT_LOAD_DURATION = 2000L
        private const val IMAGE_LOAD_DELAY = 100L
        private const val ITEM_SECTION = 0
        private const val ITEM_MEDIUM_VIDEO_PORTRAIT = 1
        private const val ITEM_MEDIUM_PHOTO = 2
    }

    private val config = activity.config
    private val viewType = config.getFolderViewType(if (config.showAll) SHOW_ALL else path)
    private val isListViewType = viewType == VIEW_TYPE_LIST
    private var visibleItemPaths = ArrayList<String>()
    private var rotatedImagePaths = ArrayList<String>()
    private var loadImageInstantly = false
    private var delayHandler = Handler(Looper.getMainLooper())
    private var currentMediaHash = media.hashCode()
    private val hasOTGConnected = activity.hasOTGConnected()

    private var scrollHorizontally = config.scrollHorizontally
    private var animateGifs = config.animateGifs
    private var cropThumbnails = config.cropThumbnails
    private var displayFilenames = config.displayFileNames
    private var showFileTypes = config.showThumbnailFileTypes

    init {
        setupDragListener(true)
        enableInstantLoad()
    }

    override fun getActionMenuId() = R.menu.cab_media

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutType = if (viewType == ITEM_SECTION) {
            R.layout.thumbnail_section
        } else {
            if (isListViewType) {
                if (viewType == ITEM_MEDIUM_PHOTO) {
                    R.layout.photo_item_list
                } else {
                    R.layout.video_item_list
                }
            } else {
                if (viewType == ITEM_MEDIUM_PHOTO) {
                    R.layout.photo_item_grid
                } else {
                    R.layout.video_item_grid
                }
            }
        }
        return createViewHolder(layoutType, parent)
    }

    override fun onBindViewHolder(holder: MyRecyclerViewAdapter.ViewHolder, position: Int) {
        val tmbItem = media.getOrNull(position) ?: return
        if (tmbItem is Medium) {
            visibleItemPaths.add(tmbItem.path)
        }

        val allowLongPress = (!isAGetIntent || allowMultiplePicks) && tmbItem is Medium
        holder.bindView(tmbItem, tmbItem is Medium, allowLongPress) { itemView, _ ->
            if (tmbItem is Medium) {
                setupThumbnail(itemView, tmbItem)
            } else {
                setupSection(itemView, tmbItem as ThumbnailSection)
            }
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = media.size

    override fun getItemViewType(position: Int): Int {
        val tmbItem = media[position]
        return when {
            tmbItem is ThumbnailSection -> ITEM_SECTION
            (tmbItem as Medium).isVideo() || tmbItem.isPortrait() -> ITEM_MEDIUM_VIDEO_PORTRAIT
            else -> ITEM_MEDIUM_PHOTO
        }
    }

    override fun prepareActionMode(menu: Menu) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) {
            return
        }

        val isOneItemSelected = isOneItemSelected()
        val selectedPaths = selectedItems.map { it.path } as ArrayList<String>
        val isInRecycleBin = selectedItems.firstOrNull()?.getIsInRecycleBin() == true
        menu.apply {
            findItem(R.id.cab_rename).isVisible = !isInRecycleBin
            findItem(R.id.cab_add_to_favorites).isVisible = !isInRecycleBin
            findItem(R.id.cab_fix_date_taken).isVisible = !isInRecycleBin
            findItem(R.id.cab_move_to).isVisible = !isInRecycleBin
            findItem(R.id.cab_open_with).isVisible = isOneItemSelected
            findItem(R.id.cab_confirm_selection).isVisible =
                isAGetIntent && allowMultiplePicks && selectedKeys.isNotEmpty()
            findItem(R.id.cab_restore_recycle_bin_files).isVisible =
                selectedPaths.all { it.startsWith(activity.recycleBinPath) }
            findItem(R.id.cab_create_shortcut).isVisible = isOreoPlus() && isOneItemSelected

            checkHideBtnVisibility(this, selectedItems)
            checkFavoriteBtnVisibility(this, selectedItems)
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_confirm_selection -> confirmSelection()
            R.id.cab_properties -> showProperties()
            R.id.cab_rename -> renameFile()
            R.id.cab_edit -> editFile()
            R.id.cab_hide -> toggleFileVisibility(true)
            R.id.cab_unhide -> toggleFileVisibility(false)
            R.id.cab_add_to_favorites -> toggleFavorites(true)
            R.id.cab_remove_from_favorites -> toggleFavorites(false)
            R.id.cab_restore_recycle_bin_files -> restoreFiles()
            R.id.cab_share -> shareMedia()
            R.id.cab_rotate_right -> rotateSelection(90)
            R.id.cab_rotate_left -> rotateSelection(270)
            R.id.cab_rotate_one_eighty -> rotateSelection(180)
            R.id.cab_copy_to -> copyMoveTo(true)
            R.id.cab_move_to -> moveFilesTo()
            R.id.cab_create_shortcut -> createShortcut()
            R.id.cab_select_all -> selectAll()
            R.id.cab_open_with -> openPath()
            R.id.cab_fix_date_taken -> fixDateTaken()
            R.id.cab_set_as -> setAs()
            R.id.cab_delete -> checkDeleteConfirmation()
        }
    }

    override fun getSelectableItemCount() = media.filterIsInstance<Medium>().size

    override fun getIsItemSelectable(position: Int) = !isASectionTitle(position)

    override fun getItemSelectionKey(position: Int) =
        (media.getOrNull(position) as? Medium)?.path?.hashCode()

    override fun getItemKeyPosition(key: Int) =
        media.indexOfFirst { (it as? Medium)?.path?.hashCode() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed) {
            val itemView = holder.itemView
            visibleItemPaths.remove(itemView.medium_name?.tag)
            val tmb = itemView.medium_thumbnail
            if (tmb != null) {
                Glide.with(activity).clear(tmb)
            }
        }
    }

    fun isASectionTitle(position: Int) = media.getOrNull(position) is ThumbnailSection

    private fun checkHideBtnVisibility(menu: Menu, selectedItems: ArrayList<Medium>) {
        val isInRecycleBin = selectedItems.firstOrNull()?.getIsInRecycleBin() == true
        menu.findItem(R.id.cab_hide).isVisible =
            !isInRecycleBin && selectedItems.any { !it.isHidden() }
        menu.findItem(R.id.cab_unhide).isVisible =
            !isInRecycleBin && selectedItems.any { it.isHidden() }
    }

    private fun checkFavoriteBtnVisibility(menu: Menu, selectedItems: ArrayList<Medium>) {
        menu.findItem(R.id.cab_add_to_favorites).isVisible =
            selectedItems.none { it.getIsInRecycleBin() } && selectedItems.any { !it.isFavorite }
        menu.findItem(R.id.cab_remove_from_favorites).isVisible =
            selectedItems.none { it.getIsInRecycleBin() } && selectedItems.any { it.isFavorite }
    }

    private fun confirmSelection() {
        listener?.selectedPaths(getSelectedPaths())
    }

    private fun showProperties() {
        if (selectedKeys.size <= 1) {
            val path = getFirstSelectedItemPath() ?: return
            PropertiesDialog(activity, path, config.shouldShowHidden)
        } else {
            val paths = getSelectedPaths()
            PropertiesDialog(activity, paths, config.shouldShowHidden)
        }
    }

    private fun renameFile() {
        if (selectedKeys.size == 1) {
            val oldPath = getFirstSelectedItemPath() ?: return
            RenameItemDialog(activity = activity, path = oldPath) {
                ensureBackgroundThread {
                    activity.updateDBMediaPath(oldPath, it)

                    activity.runOnUiThread {
                        enableInstantLoad()
                        listener?.refreshItems()
                        finishActMode()
                    }
                }
            }
        } else {
            RenameDialog(
                activity = activity,
                paths = getSelectedPaths(),
                useMediaFileExtension = true
            ) {
                enableInstantLoad()
                listener?.refreshItems()
                finishActMode()
            }
        }
    }

    private fun editFile() {
        val path = getFirstSelectedItemPath() ?: return
        activity.openEditor(path)
    }

    private fun openPath() {
        val path = getFirstSelectedItemPath() ?: return
        activity.openPath(path, true)
    }

    private fun setAs() {
        val path = getFirstSelectedItemPath() ?: return
        activity.setAs(path)
    }

    private fun toggleFileVisibility(hide: Boolean) {
        ensureBackgroundThread {
            getSelectedItems().forEach {
                activity.toggleFileVisibility(it.path, hide)
            }
            activity.runOnUiThread {
                listener?.refreshItems()
                finishActMode()
            }
        }
    }

    private fun toggleFavorites(add: Boolean) {
        ensureBackgroundThread {
            getSelectedItems().forEach {
                it.isFavorite = add
                activity.updateFavorite(it.path, add)
            }
            activity.runOnUiThread {
                listener?.refreshItems()
                finishActMode()
            }
        }
    }

    private fun restoreFiles() {
        activity.restoreRecycleBinPaths(getSelectedPaths()) {
            listener?.refreshItems()
            finishActMode()
        }
    }

    private fun shareMedia() {
        if (selectedKeys.size == 1 && selectedKeys.first() != -1) {
            activity.shareMediumPath(getSelectedItems().first().path)
        } else if (selectedKeys.size > 1) {
            activity.shareMediaPaths(getSelectedPaths())
        }
    }

    private fun rotateSelection(degrees: Int) {
        activity.toast(R.string.saving)
        ensureBackgroundThread {
            val paths = getSelectedPaths().filter { it.isImageFast() }
            var fileCnt = paths.size
            rotatedImagePaths.clear()
            paths.forEach {
                rotatedImagePaths.add(it)
                activity.saveRotatedImageToFile(
                    oldPath = it,
                    newPath = it,
                    degrees = degrees,
                    showToasts = true
                ) {
                    fileCnt--
                    if (fileCnt == 0) {
                        activity.runOnUiThread {
                            listener?.refreshItems()
                            finishActMode()
                        }
                    }
                }
            }
        }
    }

    private fun moveFilesTo() {
        activity.handleDeletePasswordProtection {
            copyMoveTo(false)
        }
    }

    private fun copyMoveTo(isCopyOperation: Boolean) {
        val paths = getSelectedPaths()

        val recycleBinPath = activity.recycleBinPath
        val fileDirItems =
            paths.asSequence().filter { isCopyOperation || !it.startsWith(recycleBinPath) }.map {
                FileDirItem(it, it.getFilenameFromPath())
            }.toMutableList() as ArrayList

        if (!isCopyOperation && paths.any { it.startsWith(recycleBinPath) }) {
            activity.toast(R.string.moving_recycle_bin_items_disabled, Toast.LENGTH_LONG)
        }

        if (fileDirItems.isEmpty()) {
            return
        }

        activity.tryCopyMoveFilesTo(fileDirItems, isCopyOperation) {
            val destinationPath = it
            config.tempFolderPath = ""
            activity.applicationContext.rescanFolderMedia(destinationPath)
            activity.applicationContext.rescanFolderMedia(fileDirItems.first().getParentPath())

            val newPaths = fileDirItems.map { "$destinationPath/${it.name}" }
                .toMutableList() as ArrayList<String>
            activity.rescanPaths(newPaths) {
                activity.fixDateTaken(paths = newPaths, showToasts = false)
            }
            if (!isCopyOperation) {
                listener?.refreshItems()
                activity.updateFavoritePaths(
                    fileDirItems = fileDirItems,
                    destination = destinationPath
                )
            }
        }
    }

    @SuppressLint("NewApi")
    private fun createShortcut() {
        val manager = activity.getSystemService(ShortcutManager::class.java)
        if (manager.isRequestPinShortcutSupported) {
            val path = getSelectedPaths().first()
            val drawable = resources.getDrawable(R.drawable.shortcut_image).mutate()
            activity.getShortcutImage(path, drawable) {
                val intent = Intent(activity, ViewPagerActivity::class.java).apply {
                    putExtra(PATH, path)
                    putExtra(SHOW_ALL, config.showAll)
                    putExtra(SHOW_FAVORITES, path == FAVORITES)
                    putExtra(SHOW_RECYCLE_BIN, path == RECYCLE_BIN)
                    action = Intent.ACTION_VIEW
                    flags =
                        flags or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }

                val shortcut = ShortcutInfo.Builder(activity, path)
                    .setShortLabel(path.getFilenameFromPath())
                    .setIcon(Icon.createWithBitmap(drawable.convertToBitmap()))
                    .setIntent(intent)
                    .build()

                manager.requestPinShortcut(shortcut, null)
            }
        }
    }

    private fun fixDateTaken() {
        ensureBackgroundThread {
            activity.fixDateTaken(paths = getSelectedPaths(), showToasts = true) {
                listener?.refreshItems()
                finishActMode()
            }
        }
    }

    private fun checkDeleteConfirmation() {
        if (config.isDeletePasswordProtectionOn) {
            activity.handleDeletePasswordProtection {
                deleteFiles()
            }
        } else if (config.tempSkipDeleteConfirmation || config.skipDeleteConfirmation) {
            deleteFiles()
        } else {
            askConfirmDelete()
        }
    }

    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size
        val firstPath = getSelectedPaths().first()
        val items = if (itemsCnt == 1) {
            "\"${firstPath.getFilenameFromPath()}\""
        } else {
            resources.getQuantityString(R.plurals.delete_items, itemsCnt, itemsCnt)
        }

        val isRecycleBin = firstPath.startsWith(activity.recycleBinPath)
        val baseString =
            if (config.useRecycleBin && !isRecycleBin) R.string.move_to_recycle_bin_confirmation else R.string.deletion_confirmation
        val question = String.format(resources.getString(baseString), items)
        DeleteWithRememberDialog(activity, question) {
            config.tempSkipDeleteConfirmation = it
            deleteFiles()
        }
    }

    private fun deleteFiles() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val sAFPath = getSelectedPaths().firstOrNull { activity.needsStupidWritePermissions(it) }
            ?: getFirstSelectedItemPath() ?: return
        activity.handleSAFDialog(sAFPath) {
            if (!it) {
                return@handleSAFDialog
            }

            val fileDirItems = ArrayList<FileDirItem>(selectedKeys.size)
            val removeMedia = ArrayList<Medium>(selectedKeys.size)
            val positions = getSelectedItemPositions()

            getSelectedItems().forEach {
                fileDirItems.add(FileDirItem(it.path, it.name))
                removeMedia.add(it)
            }

            media.removeAll(removeMedia)
            listener?.tryDeleteFiles(fileDirItems)
            listener?.updateMediaGridDecoration(media)
            removeSelectedItems(positions)
            currentMediaHash = media.hashCode()
        }
    }

    private fun getSelectedItems() =
        selectedKeys.mapNotNull { getItemWithKey(it) } as ArrayList<Medium>

    private fun getSelectedPaths() = getSelectedItems().map { it.path } as ArrayList<String>

    private fun getFirstSelectedItemPath() = getItemWithKey(selectedKeys.first())?.path

    private fun getItemWithKey(key: Int): Medium? =
        media.firstOrNull { (it as? Medium)?.path?.hashCode() == key } as? Medium

    fun updateMedia(newMedia: ArrayList<ThumbnailItem>) {
        val thumbnailItems = newMedia.clone() as ArrayList<ThumbnailItem>
        if (thumbnailItems.hashCode() != currentMediaHash) {
            currentMediaHash = thumbnailItems.hashCode()
            media = thumbnailItems
            enableInstantLoad()
            notifyDataSetChanged()
            finishActMode()
        }
    }

    fun updateDisplayFilenames(displayFilenames: Boolean) {
        this.displayFilenames = displayFilenames
        enableInstantLoad()
        notifyDataSetChanged()
    }

    fun updateAnimateGifs(animateGifs: Boolean) {
        this.animateGifs = animateGifs
        notifyDataSetChanged()
    }

    fun updateCropThumbnails(cropThumbnails: Boolean) {
        this.cropThumbnails = cropThumbnails
        notifyDataSetChanged()
    }

    fun updateShowFileTypes(showFileTypes: Boolean) {
        this.showFileTypes = showFileTypes
        notifyDataSetChanged()
    }

    private fun enableInstantLoad() {
        loadImageInstantly = true
        delayHandler.postDelayed({
            loadImageInstantly = false
        }, INSTANT_LOAD_DURATION)
    }

    fun getItemBubbleText(
        position: Int,
        sorting: Int,
        dateFormat: String,
        timeFormat: String
    ): String {
        return (media[position] as? Medium)?.getBubbleText(
            sorting,
            activity,
            dateFormat,
            timeFormat
        ) ?: ""
    }

    private fun setupThumbnail(view: View, medium: Medium) {
        val isSelected = selectedKeys.contains(medium.path.hashCode())
        view.apply {
            val padding = if (config.thumbnailSpacing <= 1) {
                config.thumbnailSpacing
            } else {
                0
            }

            media_item_holder.setPadding(padding, padding, padding, padding)

            play_portrait_outline?.beVisibleIf(medium.isVideo() || medium.isPortrait())
            if (medium.isVideo()) {
                play_portrait_outline?.setImageResource(R.drawable.ic_play_outline_vector)
                play_portrait_outline?.beVisible()
            } else if (medium.isPortrait()) {
                play_portrait_outline?.setImageResource(R.drawable.ic_portrait_photo_vector)
                play_portrait_outline?.beVisibleIf(showFileTypes)
            }

            if (showFileTypes && (medium.isGIF() || medium.isRaw() || medium.isSVG())) {
                file_type.setText(
                    when (medium.type) {
                        TYPE_GIFS -> R.string.gif
                        TYPE_RAWS -> R.string.raw
                        else -> R.string.svg
                    }
                )
                file_type.beVisible()
            } else {
                file_type?.beGone()
            }

            medium_name.beVisibleIf(displayFilenames || isListViewType)
            medium_name.text = medium.name
            medium_name.tag = medium.path

            val showVideoDuration = medium.isVideo() && config.showThumbnailVideoDuration
            if (showVideoDuration) {
                tvVideoDuration?.text = medium.videoDuration.getFormattedDuration()
            }
            tvVideoDuration?.beVisibleIf(showVideoDuration)

            medium_check?.beVisibleIf(isSelected)
            if (isSelected) {
                medium_check?.background?.applyColorFilter(adjustedPrimaryColor)
                medium_check.applyColorFilter(contrastColor)
            }

            if (isListViewType) {
                media_item_holder.isSelected = isSelected
            }

            var path = medium.path
            if (hasOTGConnected && context.isPathOnOTG(path)) {
                path = path.getOTGPublicPath(context)
            }

            val roundedCorners = when {
                isListViewType -> ROUNDED_CORNERS_SMALL
                config.fileRoundedCorners -> ROUNDED_CORNERS_BIG
                else -> ROUNDED_CORNERS_NONE
            }

            if (loadImageInstantly) {
                activity.loadImage(
                    type = medium.type,
                    path = path,
                    target = medium_thumbnail,
                    horizontalScroll = scrollHorizontally,
                    animateGifs = animateGifs,
                    cropThumbnails = cropThumbnails,
                    roundCorners = roundedCorners,
                    signature = medium.getKey(),
                    skipMemoryCacheAtPaths = rotatedImagePaths
                )
            } else {
                medium_thumbnail.setImageDrawable(null)
                medium_thumbnail.isHorizontalScrolling = scrollHorizontally
                delayHandler.postDelayed({
                    val isVisible = visibleItemPaths.contains(medium.path)
                    if (isVisible) {
                        activity.loadImage(
                            type = medium.type,
                            path = path,
                            target = medium_thumbnail,
                            horizontalScroll = scrollHorizontally,
                            animateGifs = animateGifs,
                            cropThumbnails = cropThumbnails,
                            roundCorners = roundedCorners,
                            signature = medium.getKey(),
                            skipMemoryCacheAtPaths = rotatedImagePaths
                        )
                    }
                }, IMAGE_LOAD_DELAY)
            }

            if (isListViewType) {
                medium_name.setTextColor(textColor)
                play_portrait_outline?.applyColorFilter(textColor)
            }
        }
    }

    private fun setupSection(view: View, section: ThumbnailSection) {
        view.apply {
            thumbnail_section.text = section.title
            thumbnail_section.setTextColor(textColor)
        }
    }
}
