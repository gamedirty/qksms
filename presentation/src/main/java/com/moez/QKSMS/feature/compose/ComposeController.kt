/*
 * Copyright (C) 2020 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.moez.QKSMS.feature.compose

import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Transformations.map
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.moez.QKSMS.R
import com.moez.QKSMS.common.Navigator
import com.moez.QKSMS.common.base.QkController
import com.moez.QKSMS.common.util.Colors
import com.moez.QKSMS.common.util.DateFormatter
import com.moez.QKSMS.common.util.extensions.autoScrollToStart
import com.moez.QKSMS.common.util.extensions.hideKeyboard
import com.moez.QKSMS.common.util.extensions.resolveThemeColor
import com.moez.QKSMS.common.util.extensions.scrapViews
import com.moez.QKSMS.common.util.extensions.setBackgroundTint
import com.moez.QKSMS.common.util.extensions.setTint
import com.moez.QKSMS.common.util.extensions.setVisible
import com.moez.QKSMS.common.util.extensions.showKeyboard
import com.moez.QKSMS.databinding.ComposeControllerBinding
import com.moez.QKSMS.extensions.Optional
import com.moez.QKSMS.extensions.asObservable
import com.moez.QKSMS.extensions.mapNotNull
import com.moez.QKSMS.feature.compose.editing.ChipsAdapter
import com.moez.QKSMS.feature.compose.injection.ComposeModule
import com.moez.QKSMS.feature.contacts.ContactsActivity
import com.moez.QKSMS.injection.appComponent
import com.moez.QKSMS.model.Attachment
import com.moez.QKSMS.model.Attachments
import com.moez.QKSMS.model.Recipient
import com.moez.QKSMS.repository.ConversationRepository
import com.moez.QKSMS.repository.MessageRepository
import com.moez.QKSMS.util.PhoneNumberUtils
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlin.collections.HashMap

class ComposeController(
    val query: String = "",
    val threadId: Long = 0,
    val addresses: List<String> = listOf(),
    val sharedText: String = "",
    val sharedAttachments: List<Attachment> = listOf()
): QkController<ComposeView, ComposeState, ComposePresenter, ComposeControllerBinding>(
        ComposeControllerBinding::inflate
), ComposeView {

    companion object {
        private const val SelectContactRequestCode = 0
        private const val TakePhotoRequestCode = 1
        private const val AttachPhotoRequestCode = 2
        private const val AttachContactRequestCode = 3

        private const val CameraDestinationKey = "camera_destination"
    }

    @Inject override lateinit var presenter: ComposePresenter

    @Inject lateinit var colors: Colors
    @Inject lateinit var context: Context
    @Inject lateinit var attachmentAdapter: AttachmentAdapter
    @Inject lateinit var chipsAdapter: ChipsAdapter
    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var dateFormatter: DateFormatter
    @Inject lateinit var messageAdapter: MessagesAdapter
    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var phoneNumberUtils: PhoneNumberUtils

    override val activityVisibleIntent: Subject<Boolean> = PublishSubject.create()
    override val chipsSelectedIntent: Subject<HashMap<String, String?>> = PublishSubject.create()
    override val chipDeletedIntent: Subject<Recipient> by lazy { chipsAdapter.chipDeleted }
    override val menuReadyIntent: Observable<Unit> = menu.map { }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val sendAsGroupIntent by lazy { binding.sendAsGroupBackground.clicks() }
    override val messageClickIntent: Subject<Long> by lazy { messageAdapter.clicks }
    override val messagePartClickIntent: Subject<Long> by lazy { messageAdapter.partClicks }
    override val messagesSelectedIntent by lazy { messageAdapter.selectionChanges }
    override val cancelSendingIntent: Subject<Long> by lazy { messageAdapter.cancelSending }
    override val attachmentDeletedIntent: Subject<Attachment> by lazy { attachmentAdapter.attachmentDeleted }
    override val textChangedIntent by lazy { binding.message.textChanges() }
    override val attachIntent by lazy { Observable.merge(binding.attach.clicks(), binding.attachingBackground.clicks()) }
    override val cameraIntent by lazy { Observable.merge(binding.camera.clicks(), binding.cameraLabel.clicks()) }
    override val galleryIntent by lazy { Observable.merge(binding.gallery.clicks(), binding.galleryLabel.clicks()) }
    override val scheduleIntent by lazy { Observable.merge(binding.schedule.clicks(), binding.scheduleLabel.clicks()) }
    override val attachContactIntent by lazy { Observable.merge(binding.contact.clicks(), binding.contactLabel.clicks()) }
    override val attachmentSelectedIntent: Subject<Uri> = PublishSubject.create()
    override val contactSelectedIntent: Subject<Uri> = PublishSubject.create()
    override val inputContentIntent by lazy { binding.message.inputContentSelected }
    override val scheduleSelectedIntent: Subject<Long> = PublishSubject.create()
    override val changeSimIntent by lazy { binding.sim.clicks() }
    override val scheduleCancelIntent by lazy { binding.scheduledCancel.clicks() }
    override val sendIntent by lazy { binding.send.clicks() }
    override val viewQksmsPlusIntent: Subject<Unit> = PublishSubject.create()
    override val backPressedIntent: Subject<Unit> = PublishSubject.create()

    private val threadIdSubject: Subject<Long> = PublishSubject.create()
    private val theme = threadIdSubject
            .distinctUntilChanged()
            .switchMap { threadId ->
                val conversation = conversationRepo.getConversation(threadId)
                when {
                    conversation == null -> Observable.just(Optional(null))

                    conversation.recipients.size == 1 -> Observable.just(Optional(conversation.recipients.first()))

                    else -> messageRepo.getLastIncomingMessage(conversation.id)
                            .asObservable()
                            .mapNotNull { messages -> messages.firstOrNull() }
                            .distinctUntilChanged { message -> message.address }
                            .mapNotNull { message ->
                                conversation.recipients.find { recipient ->
                                    phoneNumberUtils.compare(recipient.address, message.address)
                                }
                            }
                            .map { recipient -> Optional(recipient) }
                            .startWith(Optional(conversation.recipients.firstOrNull()))
                            .distinctUntilChanged()
                }
            }
            .switchMap { colors.themeObservable(it.value) }
            .share()

    private var cameraDestination: Uri? = null

    init {
        appComponent
                .composeBuilder()
                .composeModule(ComposeModule((this)))
                .build()
                .inject(this)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        showBackButton(true)
        setHasOptionsMenu(true)
        presenter.bindIntents(this)
    }

    override fun onViewCreated() {
        binding.contentView.layoutTransition = LayoutTransition().apply {
            disableTransitionType(LayoutTransition.CHANGING)
        }

        chipsAdapter.view = binding.chips

        binding.chips.itemAnimator = null
        binding.chips.layoutManager = FlexboxLayoutManager(context)

        messageAdapter.autoScrollToStart(binding.messageList)
        messageAdapter.emptyView = binding.messagesEmpty

        binding.messageList.setHasFixedSize(true)
        binding.messageList.adapter = messageAdapter

        binding.attachments.adapter = attachmentAdapter

        binding.message.supportsInputContent = true

        theme
                .doOnNext { binding.loading.setTint(it.theme) }
                .doOnNext { binding.attach.setBackgroundTint(it.theme) }
                .doOnNext { binding.attach.setTint(it.textPrimary) }
                .doOnNext { messageAdapter.theme = it }
                .doOnNext { activity?.invalidateOptionsMenu() }
                .autoDisposable(scope())
                .subscribe()

        activity!!.window.callback = ComposeWindowCallback(activity!!.window.callback, activity!!)

        // These theme attributes don't apply themselves on API 21
        if (Build.VERSION.SDK_INT <= 22) {
            binding.messageBackground.setBackgroundTint(context.resolveThemeColor(R.attr.bubbleColor))
        }
    }

    override fun onActivityStarted(activity: Activity) {
        super.onActivityStarted(activity)
        activityVisibleIntent.onNext(true)
    }

    override fun onActivityPaused(activity: Activity) {
        super.onActivityPaused(activity)
        activityVisibleIntent.onNext(false)
    }

    override fun render(state: ComposeState) {
        if (state.hasError) {
            activity!!.finish()
            return
        }

        threadIdSubject.onNext(state.threadId)

        setTitle(when {
            state.selectedMessages > 0 -> context.getString(R.string.compose_title_selected, state.selectedMessages)
            state.query.isNotEmpty() -> state.query
            else -> state.conversationtitle
        })

        binding.toolbarSubtitle.setVisible(state.query.isNotEmpty())
        binding.toolbarSubtitle.text = context.getString(R.string.compose_subtitle_results, state.searchSelectionPosition,
                state.searchResults)

        binding.toolbarTitle.setVisible(!state.editingMode)
        binding.chips.setVisible(state.editingMode)
        binding.composeBar.setVisible(!state.loading)

        // Don't set the adapters unless needed
        if (state.editingMode && binding.chips.adapter == null) binding.chips.adapter = chipsAdapter

        binding.toolbar.menu.findItem(R.id.add)?.isVisible = state.editingMode
        binding.toolbar.menu.findItem(R.id.call)?.isVisible = !state.editingMode && state.selectedMessages == 0
                && state.query.isEmpty()
        binding.toolbar.menu.findItem(R.id.info)?.isVisible = !state.editingMode && state.selectedMessages == 0
                && state.query.isEmpty()
        binding.toolbar.menu.findItem(R.id.copy)?.isVisible = !state.editingMode && state.selectedMessages > 0
        binding.toolbar.menu.findItem(R.id.details)?.isVisible = !state.editingMode && state.selectedMessages == 1
        binding.toolbar.menu.findItem(R.id.delete)?.isVisible = !state.editingMode && state.selectedMessages > 0
        binding.toolbar.menu.findItem(R.id.forward)?.isVisible = !state.editingMode && state.selectedMessages == 1
        binding.toolbar.menu.findItem(R.id.previous)?.isVisible = state.selectedMessages == 0 && state.query.isNotEmpty()
        binding.toolbar.menu.findItem(R.id.next)?.isVisible = state.selectedMessages == 0 && state.query.isNotEmpty()
        binding.toolbar.menu.findItem(R.id.clear)?.isVisible = state.selectedMessages == 0 && state.query.isNotEmpty()

        chipsAdapter.data = state.selectedChips

        binding.loading.setVisible(state.loading)

        binding.sendAsGroup.setVisible(state.editingMode && state.selectedChips.size >= 2)
        binding.sendAsGroupSwitch.isChecked = state.sendAsGroup

        binding.messageList.setVisible(!state.editingMode || state.sendAsGroup || state.selectedChips.size == 1)
        messageAdapter.data = state.messages
        messageAdapter.highlight = state.searchSelectionId

        binding.scheduledGroup.isVisible = state.scheduled != 0L
        binding.scheduledTime.text = dateFormatter.getScheduledTimestamp(state.scheduled)

        binding.attachments.setVisible(state.attachments.isNotEmpty())
        attachmentAdapter.data = state.attachments

        binding.attach.animate().rotation(if (state.attaching) 135f else 0f).start()
        binding.attaching.isVisible = state.attaching

        binding.counter.text = state.remaining
        binding.counter.setVisible(binding.counter.text.isNotBlank())

        binding.sim.setVisible(state.subscription != null)
        binding.sim.contentDescription = context.getString(R.string.compose_sim_cd, state.subscription?.displayName)
        binding.simIndex.text = state.subscription?.simSlotIndex?.plus(1)?.toString()

        binding.send.isEnabled = state.canSend
        binding.send.imageAlpha = if (state.canSend) 255 else 128
    }

    override fun clearSelection() = messageAdapter.clearSelection()

    override fun showDetails(details: String) {
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.compose_details_title)
                .setMessage(details)
                .setCancelable(true)
                .show()
    }

    override fun requestDefaultSms() {
        navigator.showDefaultSmsDialog(activity!!)
    }

    override fun requestStoragePermission() {
        ActivityCompat.requestPermissions(activity!!, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
    }

    override fun requestSmsPermission() {
        ActivityCompat.requestPermissions(activity!!, arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS), 0)
    }

    override fun requestDatePicker() {
        val calendar = Calendar.getInstance()
        DatePickerDialog(activity!!, DatePickerDialog.OnDateSetListener { _, year, month, day ->
            TimePickerDialog(activity!!, TimePickerDialog.OnTimeSetListener { _, hour, minute ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                scheduleSelectedIntent.onNext(calendar.timeInMillis)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), DateFormat.is24HourFormat(context))
                    .show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()

        // On some devices, the keyboard can cover the date picker
        binding.message.hideKeyboard()
    }

    override fun requestContact() {
        val intent = Intent(Intent.ACTION_PICK)
                .setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)

        startActivityForResult(Intent.createChooser(intent, null), AttachContactRequestCode)
    }

    override fun showContacts(sharing: Boolean, chips: List<Recipient>) {
        binding.message.hideKeyboard()
        val serialized = HashMap(chips.associate { chip -> chip.address to chip.contact?.lookupKey })
        val intent = Intent(context, ContactsActivity::class.java)
                .putExtra(ContactsActivity.SharingKey, sharing)
                .putExtra(ContactsActivity.ChipsKey, serialized)
        startActivityForResult(intent, SelectContactRequestCode)
    }

    override fun themeChanged() {
        binding.messageList.scrapViews()
    }

    override fun showKeyboard() {
        binding.message.postDelayed({
            binding.message.showKeyboard()
        }, 200)
    }

    override fun requestCamera() {
        cameraDestination = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                .let { timestamp -> ContentValues().apply { put(MediaStore.Images.Media.TITLE, timestamp) } }
                .let { cv -> context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, cameraDestination)
        startActivityForResult(Intent.createChooser(intent, null), TakePhotoRequestCode)
    }

    override fun requestGallery() {
        val intent = Intent(Intent.ACTION_PICK)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setType("image/*")
        startActivityForResult(Intent.createChooser(intent, null), AttachPhotoRequestCode)
    }

    override fun setDraft(draft: String) {
        binding.message.setText(draft)
        binding.message.setSelection(draft.length)
    }

    override fun scrollToMessage(id: Long) {
        messageAdapter.data?.second
                ?.indexOfLast { message -> message.id == id }
                ?.takeIf { position -> position != -1 }
                ?.let(binding.messageList::scrollToPosition)
    }

    override fun showQksmsPlusSnackbar(message: Int) {
        Snackbar.make(binding.contentView, message, Snackbar.LENGTH_LONG).run {
            setAction(R.string.button_more) { viewQksmsPlusIntent.onNext(Unit) }
            setActionTextColor(colors.theme().theme)
            show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.compose, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        theme
                .autoDisposable(scope())
                .subscribe { theme -> menu.findItem(R.id.call)?.setTint(theme.theme) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        optionsItemIntent.onNext(item.itemId)
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode == SelectContactRequestCode -> {
                chipsSelectedIntent.onNext(data?.getSerializableExtra(ContactsActivity.ChipsKey)
                        ?.let { serializable -> serializable as? HashMap<String, String?> }
                        ?: hashMapOf())
            }
            requestCode == TakePhotoRequestCode && resultCode == Activity.RESULT_OK -> {
                cameraDestination?.let(attachmentSelectedIntent::onNext)
            }
            requestCode == AttachPhotoRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.clipData?.itemCount
                        ?.let { count -> 0 until count }
                        ?.mapNotNull { i -> data.clipData?.getItemAt(i)?.uri }
                        ?.forEach(attachmentSelectedIntent::onNext)
                        ?: data?.data?.let(attachmentSelectedIntent::onNext)
            }
            requestCode == AttachContactRequestCode && resultCode == Activity.RESULT_OK -> {
                data?.data?.let(contactSelectedIntent::onNext)
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(CameraDestinationKey, cameraDestination)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        cameraDestination = savedInstanceState.getParcelable(CameraDestinationKey)
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun handleBack(): Boolean {
        backPressedIntent.onNext(Unit)
        return true
    }

}
